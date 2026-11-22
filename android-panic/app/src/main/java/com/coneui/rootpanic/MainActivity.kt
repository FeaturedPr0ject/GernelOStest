package com.coneui.rootpanic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private val AppBackground = Color(0xFF101318)
private val AppSurface = Color(0xFF1B2028)
private val AppSurfaceAlt = Color(0xFF252D3A)
private val AppPrimary = Color(0xFFB8C7FF)
private val AppOnPrimary = Color(0xFF182D60)
private val AppText = Color(0xFFE1E5F0)
private val AppMuted = Color(0xFFAEB6C5)
private val AppWarning = Color(0xFFFFC7A8)
private const val GITHUB_REPOSITORY = "https://github.com/FeaturedPr0ject/GernelOStest"
private const val GITHUB_RELEASES_API = "https://api.github.com/repos/FeaturedPr0ject/GernelOStest/releases/latest"
private const val SHIZUKU_REQUEST_CODE = 7301

internal object SystemLogCapture {
    private const val LOG_FILE = "system-log.txt"
    private const val PID_FILE = "system-log.pid"
    private const val MAX_BYTES = 24576
    private const val MAX_LINES = 160

    fun start(context: Context): String {
        val logPath = File(context.filesDir, LOG_FILE).absolutePath
        val pidPath = File(context.filesDir, PID_FILE).absolutePath
        val script = """
            if [ "${'$'}(id -u)" != "0" ]; then
                echo ROOT_PANIC_NO_ROOT
                exit 10
            fi
            if [ -s '$pidPath' ]; then
                old_pid=$(cat '$pidPath' 2>/dev/null)
                if [ -n "${'$'}old_pid" ] && kill -0 "${'$'}old_pid" 2>/dev/null; then
                    echo LOG_CAPTURE_RUNNING
                    exit 0
                fi
            fi
            rm -f '$pidPath'
            logcat -b main -b system -b crash -v threadtime -f '$logPath' -r 128 -n 2 '*:W' >/dev/null 2>&1 &
            echo ${'$'}! > '$pidPath'
            sleep 1
            current_pid=$(cat '$pidPath' 2>/dev/null)
            if [ -n "${'$'}current_pid" ] && kill -0 "${'$'}current_pid" 2>/dev/null; then
                chmod 644 '$logPath' 2>/dev/null
                echo LOG_CAPTURE_STARTED
            else
                rm -f '$pidPath'
                echo LOG_CAPTURE_FAILED
                exit 11
            fi
        """.trimIndent()
        return runRoot(script)
    }

    fun loadLocal(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE)
            if (!file.exists()) return "Waiting for Android warning and error entries…"
            val content = file.readText()
            if (content.isBlank()) return "Waiting for Android warning and error entries…"
            content.takeLast(MAX_BYTES).lines().takeLast(MAX_LINES).joinToString("\n")
        } catch (error: Exception) {
            "Cannot read the live log: " + (error.message ?: "permission or file error")
        }
    }

    private fun runRoot(command: String): String = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode == 0) output else output.ifBlank { "Root command failed with exit code " + exitCode }
    } catch (error: Exception) {
        error.message ?: "su unavailable"
    }
}

private data class RootAccess(
    val granted: Boolean,
    val provider: String,
    val details: String
)

private data class RootResult(val exitCode: Int, val output: String)

class MainActivity : ComponentActivity() {
    private val uidFile: File by lazy { File(filesDir, "systemui_uid.tmp") }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == SHIZUKU_REQUEST_CODE) runOnUiThread { refreshShizukuStatus() }
    }

    private var shizukuConnected by mutableStateOf(false)
    private var shizukuPermissionGranted by mutableStateOf(false)
    private var shizukuProvider by mutableStateOf("Shizuku service not found")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        refreshShizukuStatus()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AppPrimary,
                    onPrimary = AppOnPrimary,
                    background = AppBackground,
                    surface = AppSurface,
                    onSurface = AppText,
                    secondary = Color(0xFFADCBA8)
                )
            ) {
                RootPanicApp(
                    uidFile = uidFile,
                    shizukuConnected = shizukuConnected,
                    shizukuPermissionGranted = shizukuPermissionGranted,
                    shizukuProvider = shizukuProvider,
                    onKernelDiagnostics = { runRootAction(::kernelDiagnostics) },
                    onKernelPanic = { runRootAction(::triggerPanic) },
                    onKillSystemUi = { runRootAction(::killSystemUi) },
                    onStartSystemLog = { runRootAction { SystemLogCapture.start(this@MainActivity) } },
                    onCheckRoot = { runRootAccessCheck() },
                    onRequestShizuku = { requestShizukuPermission() },
                    onRefreshShizuku = { refreshShizukuStatus() },
                    onCheckUpdates = { withContext(Dispatchers.IO) { ReleaseUpdater.check(this@MainActivity) } },
                    onInstallUpdate = { update -> withContext(Dispatchers.IO) { ReleaseUpdater.downloadAndInstall(this@MainActivity, update, shizukuConnected, shizukuPermissionGranted) } },
                    onOpenGithub = { openUrl("https://github.com/FeaturedPr0ject") },
                    onOpenShizuku = { openUrl("https://shizuku.rikka.app/download/") }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuStatus()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun refreshShizukuStatus() {
        try {
            shizukuConnected = Shizuku.pingBinder()
            if (!shizukuConnected) {
                shizukuPermissionGranted = false
                shizukuProvider = if (isPackageInstalled("moe.shizuku.privileged.api")) {
                    "Shizuku is installed, but its service is not running"
                } else {
                    "Shizuku / Sui service not found"
                }
                return
            }
            shizukuPermissionGranted = !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val serverUid = Shizuku.getUid()
            val source = when (serverUid) {
                0 -> "Root service (Sui / Shizuku)"
                2000 -> "ADB shell service (Shizuku)"
                else -> "Shizuku service UID " + serverUid
            }
            shizukuProvider = source + if (shizukuPermissionGranted) {
                " • permission granted"
            } else {
                " • permission not granted"
            }
        } catch (error: Exception) {
            shizukuConnected = false
            shizukuPermissionGranted = false
            shizukuProvider = error.message ?: "Shizuku binder is unavailable"
        }
    }

    private fun requestShizukuPermission(): String {
        return try {
            if (!Shizuku.pingBinder()) {
                refreshShizukuStatus()
                "Start Shizuku or Sui first, then check again."
            } else if (Shizuku.isPreV11()) {
                "This Shizuku server is too old. Update Shizuku to API 11 or newer."
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshShizukuStatus()
                "Shizuku permission is already granted."
            } else {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                "Permission request sent to Shizuku."
            }
        } catch (error: Exception) {
            "Shizuku request failed: " + (error.message ?: "unknown error")
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, 0)
            info.packageName.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun runRootAction(action: () -> String): String =
        withContext(Dispatchers.IO) { action() }

    private suspend fun runRootAccessCheck(): RootAccess =
        withContext(Dispatchers.IO) { checkRootAccess() }

    private fun checkRootAccess(): RootAccess {
        val script = """
            if [ "${'$'}(id -u)" != "0" ]; then
                echo ROOT_NOT_GRANTED
                exit 10
            fi
            echo ROOT_GRANTED
            if command -v magisk >/dev/null 2>&1 || [ -d /data/adb/magisk ]; then
                echo ROOT_PROVIDER_MAGISK
            elif [ -d /data/adb/ksu ] || [ -x /data/adb/ksud ]; then
                echo ROOT_PROVIDER_KERNELSU
            elif [ -d /data/adb/ap ] || [ -x /data/adb/apd ]; then
                echo ROOT_PROVIDER_APATCH
            else
                echo ROOT_PROVIDER_SU
            fi
            echo SU_PATH=$(command -v su 2>/dev/null)
            echo SELINUX=$(getenforce 2>/dev/null)
        """.trimIndent()
        val result = runRootCommand(script)
        val output = result.output
        val granted = output.contains("ROOT_GRANTED") && result.exitCode == 0
        val provider = when {
            output.contains("ROOT_PROVIDER_MAGISK") -> "Magisk"
            output.contains("ROOT_PROVIDER_KERNELSU") -> "KernelSU"
            output.contains("ROOT_PROVIDER_APATCH") -> "APatch"
            output.contains("ROOT_PROVIDER_SU") -> "su provider (not identified)"
            else -> "No root provider detected"
        }
        return RootAccess(
            granted = granted,
            provider = if (granted) provider else "No app has granted Root access",
            details = output.ifBlank { "No response from su" }
        )
    }

    private fun kernelDiagnostics(): String {
        val script = """
            echo "ROOT_UID=$(id -u)"
            echo "DEVICE=$(getprop ro.product.manufacturer) $(getprop ro.product.model)"
            echo "ANDROID=$(getprop ro.build.version.release) / SDK $(getprop ro.build.version.sdk)"
            echo "KERNEL=$(uname -r)"
            echo "ARCH=$(uname -m)"
            if [ -r /proc/sys/kernel/sysrq ]; then
                echo "SYSRQ_VALUE=$(cat /proc/sys/kernel/sysrq)"
            else
                echo "SYSRQ_VALUE=unavailable"
            fi
            if [ -e /proc/sysrq-trigger ]; then
                echo "SYSRQ_TRIGGER=present"
            else
                echo "SYSRQ_TRIGGER=missing"
            fi
            if [ -w /proc/sysrq-trigger ]; then
                echo "SYSRQ_WRITABLE=yes"
            else
                echo "SYSRQ_WRITABLE=no"
            fi
            echo "MEMORY=$(grep MemTotal /proc/meminfo | head -n 1)"
            echo "SELINUX=$(getenforce 2>/dev/null)"
        """.trimIndent()
        val result = runRootCommand(script)
        return if (result.exitCode == 0) result.output else "Diagnostics failed:\n" + result.output
    }

    private fun triggerPanic(): String {
        val checkScript = """
            if [ "${'$'}(id -u)" != "0" ]; then
                echo NO_ROOT
                exit 10
            fi
            if [ ! -e /proc/sys/kernel/sysrq ] || [ ! -e /proc/sysrq-trigger ]; then
                echo UNSUPPORTED
                exit 11
            fi
            if [ ! -w /proc/sysrq-trigger ]; then
                echo NOT_WRITABLE
                exit 12
            fi
            echo READY
        """.trimIndent()
        val check = runRootCommand(checkScript)
        if (!check.output.contains("READY")) {
            return when {
                check.output.contains("NO_ROOT") -> "Root permission not granted"
                check.output.contains("UNSUPPORTED") -> "Kernel SysRq panic is not supported"
                check.output.contains("NOT_WRITABLE") -> "SysRq trigger is not writable"
                else -> "Root check failed: " + check.output.ifBlank { "unknown error" }
            }
        }
        val command = runRootCommand("echo c > /proc/sysrq-trigger")
        return if (command.exitCode == 0) {
            "Panic trigger command returned. The kernel may already have restarted."
        } else {
            "Panic command failed: " + command.output
        }
    }

    private fun killSystemUi(): String {
        val rootCheck = runRootCommand("if [ \"\$(id -u)\" = \"0\" ]; then echo ROOT_OK; else echo NO_ROOT; fi")
        if (!rootCheck.output.contains("ROOT_OK")) return "Root permission not granted"

        val path = uidFile.absolutePath
        val script = """
            package_info=$(dumpsys package com.android.systemui 2>/dev/null)
            uid=$(printf '%s\n' "${'$'}package_info" | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -n 1)
            if [ -z "${'$'}uid" ]; then
                echo SYSTEMUI_UID_NOT_FOUND
                exit 2
            fi
            printf '%s\n' "${'$'}uid" > '$path'
            saved_uid=$(cat '$path' 2>/dev/null)
            if [ -z "${'$'}saved_uid" ]; then
                echo UID_TEMP_FILE_FAILED
                exit 3
            fi
            pids=$(ps -A -o UID,PID,NAME | awk -v uid="${'$'}saved_uid" '${'$'}1 == uid && ${'$'}3 ~ /^com[.]android[.]systemui(:.*)?$/ {print ${'$'}2}')
            if [ -z "${'$'}pids" ]; then
                echo SYSTEMUI_PROCESS_NOT_FOUND
                exit 4
            fi
            for pid in ${'$'}pids; do
                kill -9 "${'$'}pid" 2>/dev/null
            done
            echo "SYSTEMUI_KILLED_UID:${'$'}saved_uid"
        """.trimIndent()
        val result = runRootCommand(script)
        return when {
            result.output.contains("SYSTEMUI_KILLED_UID:") -> {
                val uid = result.output.substringAfter("SYSTEMUI_KILLED_UID:").trim()
                "SystemUI stopped. UID $uid saved temporarily."
            }
            result.output.contains("SYSTEMUI_UID_NOT_FOUND") -> "Could not find the SystemUI UID"
            result.output.contains("UID_TEMP_FILE_FAILED") -> "Could not save the temporary UID file"
            result.output.contains("SYSTEMUI_PROCESS_NOT_FOUND") -> "SystemUI process was not found for that UID"
            else -> "SystemUI action failed: " + result.output.ifBlank { "unknown error" }
        }
    }

    private fun checkLatestRelease(): UpdateCheckResult = ReleaseUpdater.check(this)

    private fun runRootCommand(command: String): RootResult = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        RootResult(process.waitFor(), output)
    } catch (error: Exception) {
        RootResult(-1, error.message ?: "su unavailable")
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootPanicApp(
    uidFile: File,
    shizukuConnected: Boolean,
    shizukuPermissionGranted: Boolean,
    shizukuProvider: String,
    onKernelDiagnostics: suspend () -> String,
    onKernelPanic: suspend () -> String,
    onKillSystemUi: suspend () -> String,
    onStartSystemLog: suspend () -> String,
    onCheckRoot: suspend () -> RootAccess,
    onRequestShizuku: () -> String,
    onRefreshShizuku: () -> Unit,
    onCheckUpdates: suspend () -> UpdateCheckResult,
    onInstallUpdate: suspend (UpdateInfo) -> String,
    onOpenGithub: () -> Unit,
    onOpenShizuku: () -> Unit
) {
    val context = LocalContext.current
    var selectedPage by remember { mutableStateOf("Welcome") }
    var status by remember { mutableStateOf("Ready") }
    var busy by remember { mutableStateOf(false) }
    var savedUid by remember { mutableStateOf(uidFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()) }
    var systemLog by remember { mutableStateOf("System Log will start automatically when opened.") }
    var diagnostics by remember { mutableStateOf("Run diagnostics to check Root and the kernel SysRq interface.") }
    var showPanicConfirm by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var rootAccess by remember { mutableStateOf(RootAccess(false, "Checking…", "Root status has not been checked yet.")) }
    var updateStatus by remember { mutableStateOf("Checking for updates…") }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    val scope = rememberCoroutineScope()

    fun <T> perform(action: suspend () -> T, onResult: (T) -> Unit = { result -> status = result.toString() }) {
        if (busy) return
        busy = true
        status = "Working…"
        scope.launch {
            try {
                onResult(action())
                savedUid = runCatching { uidFile.takeIf { it.exists() }?.readText()?.trim().orEmpty() }.getOrDefault("")
            } catch (error: Exception) {
                status = error.message ?: "Action failed"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(700)
        val result = onCheckUpdates()
        availableUpdate = result.update
        updateStatus = result.message
    }

    LaunchedEffect(selectedPage) {
        if (selectedPage == "System Log") {
            status = "Live System Log • updates every 0.4 seconds"
            val captureResult = withContext(Dispatchers.IO) { onStartSystemLog() }
            if (!captureResult.contains("LOG_CAPTURE_STARTED") && !captureResult.contains("LOG_CAPTURE_RUNNING")) {
                systemLog = "Capture unavailable: " + captureResult.ifBlank { "no response from Root" }
            }
            while (isActive && selectedPage == "System Log") {
                systemLog = withContext(Dispatchers.IO) { SystemLogCapture.loadLocal(context) }
                delay(400)
            }
        }
        if (selectedPage == "Settings") {
            rootAccess = onCheckRoot()
            onRefreshShizuku()
        }
    }

    if (showPanicConfirm) {
        AlertDialog(
            onDismissRequest = { showPanicConfirm = false },
            title = { Text("Confirm Kernel Panic") },
            text = {
                Text("This writes the SysRq crash command directly to the Linux kernel. The device will crash and restart immediately. Unsaved data may be lost and filesystem corruption is possible.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showPanicConfirm = false
                    perform(onKernelPanic)
                }) {
                    Text("Crash kernel", color = AppWarning)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicConfirm = false }) { Text("Cancel") }
            }
        )
    }

    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = AppBackground,
            topBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { drawerOpen = true }) {
                        Text("☰", color = AppText, fontSize = 23.sp)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Text("Root Panic", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = AppText, lineHeight = 19.sp)
                        Text(selectedPage, color = AppMuted, fontSize = 10.sp, lineHeight = 11.sp)
                    }
                    IconButton(onClick = { selectedPage = "Settings" }) {
                        Text("⚙", color = AppText, fontSize = 22.sp)
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                availableUpdate?.let { update ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AppSurfaceAlt),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("UPDATE AVAILABLE", color = AppPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(update.name, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Version " + update.tag + " is ready to download.", color = AppMuted, fontSize = 13.sp)
                            if (update.notes.isNotBlank()) {
                                Text(update.notes.take(350), color = AppText, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !busy,
                                    onClick = {
                                        perform({ onInstallUpdate(update) }) {
                                            updateStatus = it
                                            status = it
                                            if (it.contains("installed successfully", true)) availableUpdate = null
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Update now") }
                                TextButton(onClick = { availableUpdate = null }) { Text("Later") }
                            }
                        }
                    }
                }
                when (selectedPage) {
                    "Welcome" -> {
                        Text("WELCOME", fontSize = 13.sp, color = AppPrimary, fontWeight = FontWeight.Bold)
                        Text("Welcome to\nRoot Panic", fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.ExtraBold, color = AppText)
                        Text(
                            "A compact toolbox for checking Android privileges, inspecting system logs, and testing kernel recovery behavior.",
                            color = AppMuted,
                            fontSize = 14.sp,
                            lineHeight = 21.sp
                        )
                        Card(colors = CardDefaults.cardColors(containerColor = AppSurface), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("AUTHOR", fontSize = 11.sp, color = AppMuted, fontWeight = FontWeight.Bold)
                                Text("FeaturedPr0ject", fontSize = 20.sp, color = AppText, fontWeight = FontWeight.Bold)
                                Surface(
                                    color = AppSurfaceAlt,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.clickable(onClick = onOpenGithub)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                                    ) {
                                        Image(painter = painterResource(R.drawable.ic_github), contentDescription = "GitHub", modifier = Modifier.size(22.dp))
                                        Text("GitHub  •  @FeaturedPr0ject", color = AppText, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        Text("TOOLS", color = AppMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        HomeActionCard("Kernel Panic", "Check kernel support and run a controlled panic test", "⚡") { selectedPage = "Kernel Panic" }
                        HomeActionCard("Kill SystemUI", "Stop only the matching SystemUI processes", "▣") { selectedPage = "Kill SystemUI" }
                        HomeActionCard("System Log", "Watch Android warnings and errors live", "≋") { selectedPage = "System Log" }
                        HomeActionCard("Settings", "Check Root and Shizuku access", "⚙") { selectedPage = "Settings" }
                    }

                    "Kernel Panic" -> {
                        PageHeading("Kernel Panic", "Kernel diagnostics and SysRq crash test")
                        Text(
                            "Check the device's kernel interface before sending the crash command. Diagnostics do not crash the device.",
                            color = AppMuted,
                            fontSize = 14.sp,
                            lineHeight = 21.sp
                        )
                        ActionButton(
                            title = if (busy) "Checking…" else "Run diagnostics",
                            enabled = !busy,
                            onClick = {
                                perform(onKernelDiagnostics) {
                                    diagnostics = it
                                    status = "Kernel diagnostics completed"
                                }
                            }
                        )
                        Card(colors = CardDefaults.cardColors(containerColor = AppSurface), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                diagnostics,
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                color = AppText,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        ActionButton("PANIC NOW", !busy, { showPanicConfirm = true }, danger = true)
                        NoticeCard(
                            "DANGER",
                            "This feature intentionally crashes the Linux kernel. It can cause data loss if Android is writing files. Use it only for testing."
                        )
                    }

                    "Kill SystemUI" -> {
                        PageHeading("Kill SystemUI", "Stop only matching SystemUI processes")
                        Text(
                            "The app reads the SystemUI package UID, saves it temporarily, and targets processes whose UID and process name both match SystemUI.",
                            color = AppMuted,
                            fontSize = 14.sp,
                            lineHeight = 21.sp
                        )
                        ActionButton("Kill SystemUI", !busy, { perform(onKillSystemUi) })
                        InfoCard("Temporary UID", savedUid.ifBlank { "No UID saved" })
                        OutlinedButton(
                            onClick = {
                                uidFile.delete()
                                savedUid = ""
                                status = "Temporary UID deleted"
                            },
                            enabled = savedUid.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Delete temporary UID")
                        }
                        NoticeCard(
                            "NOTICE",
                            "Android may restart SystemUI automatically. The status bar, navigation controls, and recent-app screen may disappear briefly."
                        )
                    }

                    "System Log" -> {
                        PageHeading("System Log", "Live updates • approximately every 0.4 seconds")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Color(0xFF244333), shape = MaterialTheme.shapes.small) {
                                Text("LIVE", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Color(0xFFB4F0C5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("Automatic refresh enabled", color = AppMuted, fontSize = 12.sp)
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF090C10)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                systemLog,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                color = AppText,
                                fontSize = 9.sp,
                                lineHeight = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 160,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "Only warning and error entries are captured from main, system, and crash buffers. The log rotates at about 384 KB and this viewer keeps a small tail to reduce memory use.",
                            color = AppMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }

                    "Settings" -> {
                        PageHeading("Settings", "Access providers and app information")
                        Card(colors = CardDefaults.cardColors(containerColor = AppSurface), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("ROOT ACCESS", color = AppMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (rootAccess.granted) "Root permission granted" else "Root permission not granted",
                                    color = if (rootAccess.granted) Color(0xFF9CE2AF) else AppWarning,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text("Provider: " + rootAccess.provider, color = AppText, fontSize = 13.sp)
                                Text(rootAccess.details.take(500), color = AppMuted, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
                                OutlinedButton(
                                    onClick = {
                                        perform(onCheckRoot) {
                                            rootAccess = it
                                            status = if (it.granted) "Root access verified" else "Root access unavailable"
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Check Root permission")
                                }
                            }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = AppSurface), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("SHIZUKU / SUI", color = AppMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    when {
                                        shizukuConnected && shizukuPermissionGranted -> "Shizuku permission granted"
                                        shizukuConnected -> "Service connected • permission not granted"
                                        else -> "Shizuku service unavailable"
                                    },
                                    color = if (shizukuConnected && shizukuPermissionGranted) Color(0xFF9CE2AF) else AppWarning,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(shizukuProvider, color = AppText, fontSize = 13.sp, lineHeight = 18.sp)
                                Text(
                                    "Shizuku can run with ADB shell UID 2000 or Root UID 0. Its permission is separate from the Root permission granted by a superuser app.",
                                    color = AppMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                                Button(
                                    onClick = {
                                        status = onRequestShizuku()
                                        onRefreshShizuku()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (shizukuPermissionGranted) "Permission already granted" else "Request Shizuku permission")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onRefreshShizuku()
                                        status = "Shizuku status checked"
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Check Shizuku service")
                                }
                                TextButton(onClick = onOpenShizuku, modifier = Modifier.fillMaxWidth()) {
                                    Text("Get Shizuku")
                                }
                            }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = AppSurface), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("APP INFORMATION", color = AppMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("Author: FeaturedPr0ject", color = AppText, fontSize = 14.sp)
                                Text("GitHub: " + GITHUB_REPOSITORY, color = AppMuted, fontSize = 11.sp)
                                Text("Updates are checked automatically whenever Root Panic opens.", color = AppMuted, fontSize = 12.sp, lineHeight = 17.sp)
                                Text(
                                    "Root Panic • v" + BuildConfig.VERSION_NAME,
                                    color = AppPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp)
                                )
                                Text(updateStatus, color = AppMuted, fontSize = 12.sp, lineHeight = 18.sp)
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = {
                                        perform({ onCheckUpdates() }) {
                                            availableUpdate = it.update
                                            updateStatus = it.message
                                            status = "Update check completed"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Check for updates now") }
                                TextButton(onClick = onOpenGithub, modifier = Modifier.fillMaxWidth()) {
                                    Text("Open author GitHub profile")
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Root Panic • " + BuildConfig.VERSION_NAME,
                            color = AppMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }

                if (selectedPage != "Settings" && selectedPage != "System Log") {
                    StatusCard(status = status, busy = busy)
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        if (drawerOpen) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = AppBackground
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ROOT PANIC", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = AppText)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { drawerOpen = false }) { Text("CLOSE") }
                    }
                    Text("Choose a screen", color = AppMuted, fontSize = 13.sp)
                    HorizontalDivider(color = AppSurfaceAlt)
                    val destinations = listOf(
                        Triple("Welcome", "⌂", "App home"),
                        Triple("Kernel Panic", "⚡", "Kernel diagnostics and panic"),
                        Triple("Kill SystemUI", "▣", "Stop Android SystemUI"),
                        Triple("System Log", "≋", "Live warning and error log")
                    )
                    destinations.forEach { (page, symbol, description) ->
                        Surface(
                            color = if (selectedPage == page) AppSurfaceAlt else AppSurface,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedPage = page
                                drawerOpen = false
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 19.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(symbol, fontSize = 25.sp, color = AppPrimary)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(page, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppText)
                                    Text(description, fontSize = 12.sp, color = AppMuted)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Surface(
                        color = if (selectedPage == "Settings") AppSurfaceAlt else AppSurface,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedPage = "Settings"
                            drawerOpen = false
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚙", fontSize = 23.sp, color = AppPrimary)
                            Spacer(Modifier.width(16.dp))
                            Text("Settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text("Root Panic • " + BuildConfig.VERSION_NAME, color = AppMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = AppText)
    Text(subtitle, fontSize = 13.sp, color = AppMuted)
}

@Composable
private fun ActionButton(
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (danger) ButtonDefaults.buttonColors(
            containerColor = Color(0xFF8E3038),
            contentColor = Color.White
        ) else ButtonDefaults.buttonColors(),
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeActionCard(title: String, description: String, icon: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(icon, color = AppPrimary, fontSize = 25.sp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(description, color = AppMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, value: String) {
    Card(colors = CardDefaults.cardColors(containerColor = AppSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = AppMuted, fontSize = 12.sp)
            Text(value, color = AppText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NoticeCard(title: String, message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = AppSurfaceAlt), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = AppWarning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(message, color = AppText, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun StatusCard(status: String, busy: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = AppSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("STATUS", color = AppMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(status, color = AppText, fontSize = 13.sp)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
