package com.coneui.rootpanic

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

@Keep
class UpdateUserService : IUpdateUserService.Stub() {
    override fun installApk(apkFd: ParcelFileDescriptor, size: Long): String {
        val process = ProcessBuilder("/system/bin/pm", "install", "-r", "-S", size.toString())
            .redirectErrorStream(true)
            .start()
        ParcelFileDescriptor.AutoCloseInputStream(apkFd).use { input ->
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
    }

    override fun destroy() {
        System.exit(0)
    }
}
