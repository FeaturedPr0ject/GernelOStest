package com.coneui.rootpanic

import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.content.pm.PackageManager
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class UpdateInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val downloadUrl: String,
    val assetName: String
)

internal data class UpdateCheckResult(
    val update: UpdateInfo?,
    val message: String
)

internal object ReleaseUpdater {
    private const val RELEASE_API = "https://api.github.com/repos/FeaturedPr0ject/GernelOStest/releases/latest"
    private const val MAX_APK_SIZE = 100L * 1024L * 1024L

    fun check(): UpdateCheckResult {
        var connection: HttpURLConnection? = null
        return try {
            val active = URL(RELEASE_API).openConnection() as HttpURLConnection
            connection = active
            active.requestMethod = "GET"
            active.connectTimeout = 8000
            active.readTimeout = 8000
            active.setRequestProperty("Accept", "application/vnd.github+json")
            active.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            active.setRequestProperty("User-Agent", "Root-Panic-Android")
            if (active.responseCode !in 200..299) {
                return UpdateCheckResult(null, "Update check failed: HTTP " + active.responseCode)
            }
            val json = JSONObject(active.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name", "").trim()
            if (tag.isBlank()) return UpdateCheckResult(null, "The latest release has no version tag.")
            if (!Regex("^v?\\d+(\\.\\d+)*$").matches(tag)) {
                return UpdateCheckResult(null, "No versioned Root Panic release has been published yet.")
            }
            if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
                return UpdateCheckResult(null, "Root Panic is up to date (" + BuildConfig.VERSION_NAME + ").")
            }
            val assets = json.optJSONArray("assets")
            var selected: JSONObject? = null
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name", "")
                    val url = asset.optString("browser_download_url", "")
                    if (name.endsWith(".apk", true) && url.startsWith("https://")) {
                        if (name.equals("Root-Panic-" + tag + ".apk", true)) {
                            selected = asset
                            break
                        }
                        if (name.startsWith("Root-Panic", true) && selected == null) selected = asset
                        if (selected == null) selected = asset
                    }
                }
            }
            val asset = selected ?: return UpdateCheckResult(
                null,
                "Version " + tag + " is published, but its APK asset is not available yet."
            )
            val update = UpdateInfo(
                tag,
                json.optString("name", "Root Panic " + tag),
                json.optString("body", "").take(1200),
                asset.getString("browser_download_url"),
                asset.getString("name")
            )
            UpdateCheckResult(update, "A new version is available: " + tag)
        } catch (error: Exception) {
            UpdateCheckResult(null, "Cannot check updates: " + (error.message ?: "network unavailable"))
        } finally {
            connection?.disconnect()
        }
    }

    private fun compareVersions(first: String, second: String): Int {
        val left = Regex("\\d+").findAll(first).mapNotNull { it.value.toLongOrNull() }.toList()
        val right = Regex("\\d+").findAll(second).mapNotNull { it.value.toLongOrNull() }.toList()
        val count = maxOf(left.size, right.size)
        for (index in 0 until count) {
            val a = left.getOrElse(index) { 0L }
            val b = right.getOrElse(index) { 0L }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    fun downloadAndInstall(
        context: Context,
        update: UpdateInfo,
        shizukuConnected: Boolean,
        shizukuPermissionGranted: Boolean
    ): String {
        if (!update.downloadUrl.startsWith("https://")) return "Blocked an insecure update download."
        val apk = File(context.cacheDir, "root-panic-update.apk")
        var connection: HttpURLConnection? = null
        try {
            val active = URL(update.downloadUrl).openConnection() as HttpURLConnection
            connection = active
            active.connectTimeout = 15000
            active.readTimeout = 30000
            active.instanceFollowRedirects = true
            active.setRequestProperty("User-Agent", "Root-Panic-Android")
            if (active.responseCode !in 200..299) {
                return "APK download failed: HTTP " + active.responseCode
            }
            if (active.contentLengthLong > MAX_APK_SIZE) return "APK is too large; download was stopped."
            active.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_SIZE) {
                            apk.delete()
                            return "APK exceeded the 100 MB safety limit."
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!apk.exists() || apk.length() < 1024L) return "Downloaded APK is empty or incomplete."
        } catch (error: Exception) {
            apk.delete()
            return "APK download failed: " + (error.message ?: "network error")
        } finally {
            connection?.disconnect()
        }

        val verified = verifyApk(context, apk)
        if (verified != null) return verified

        val size = apk.length()
        val rootResult = runCatching {
            installFromProcess(ProcessBuilder("su", "-c", "pm install -r -S " + size).redirectErrorStream(true).start(), apk)
        }.getOrElse { "Root install unavailable: " + it.message }
        if (rootResult.contains("Success", true)) return "Update installed successfully through Root. Reopen Root Panic."

        if (shizukuConnected && shizukuPermissionGranted) {
            val shizukuResult = runCatching { installWithShizuku(context, apk) }
                .getOrElse { "Shizuku install unavailable: " + it.message }
            if (shizukuResult.contains("Success", true)) return "Update installed successfully through Shizuku. Reopen Root Panic."
            return "Root install failed: " + rootResult + "\nShizuku install failed: " + shizukuResult +
                "\nThe downloaded APK remains in the app cache."
        }
        return "Root install failed: " + rootResult +
            "\nGrant Shizuku permission to install automatically, or install the downloaded APK manually."
    }

    private fun verifyApk(context: Context, apk: File): String? {
        return try {
            val archive = context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            ) ?: return "Downloaded file is not a valid Android APK."
            if (archive.packageName != context.packageName) {
                return "Package mismatch. Expected " + context.packageName + ", received " + archive.packageName + "."
            }
            if (archive.longVersionCode <= BuildConfig.VERSION_CODE) {
                return "Downloaded APK is not newer than the installed version."
            }
            val installed = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val installedSigners = installed.signingInfo?.apkContentsSigners
                ?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
            val updateSigners = archive.signingInfo?.apkContentsSigners
                ?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
            if (installedSigners.isEmpty() || updateSigners.isEmpty() || installedSigners != updateSigners) {
                return "APK signature does not match this installation. Update cancelled to protect your app and data."
            }
            null
        } catch (error: Exception) {
            "Could not verify the APK: " + (error.message ?: "verification failed")
        }
    }

    private fun installWithShizuku(context: Context, apk: File): String {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return "Shizuku service or permission is unavailable."
        }
        if (Shizuku.getVersion() < 10) return "Shizuku API 10 or newer is required."
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, UpdateUserService::class.java.name)
        ).daemon(false)
            .processNameSuffix("installer")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        val connected = CountDownLatch(1)
        var remote: IUpdateUserService? = null
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote = IUpdateUserService.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                remote = null
                connected.countDown()
            }
        }
        try {
            Shizuku.bindUserService(args, serviceConnection)
            if (!connected.await(30, TimeUnit.SECONDS)) return "Timed out while starting the Shizuku installer service."
            val service = remote ?: return "Shizuku installer service disconnected."
            ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                return service.installApk(descriptor, apk.length())
            }
        } finally {
            runCatching { Shizuku.unbindUserService(args, serviceConnection, true) }
        }
    }

    private fun installFromProcess(process: Process, apk: File): String {
        try {
            apk.inputStream().use { input ->
                process.outputStream.use { output -> input.copyTo(output) }
            }
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "Package installer timed out."
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            return if (process.exitValue() == 0 && output.contains("Success", true)) {
                output.ifBlank { "Success" }
            } else {
                output.ifBlank { "Package installer exited with code " + process.exitValue() + "." }
            }
        } catch (error: Exception) {
            process.destroy()
            return error.message ?: "Package installer failed."
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
