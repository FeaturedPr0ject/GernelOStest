package com.coneui.rootpanic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

class BootCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            File(context.filesDir, "systemui_uid.tmp").delete()
            File(context.filesDir, "system-log.pid").delete()
            val pending = goAsync()
            Thread {
                try {
                    SystemLogCapture.start(context.applicationContext)
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
