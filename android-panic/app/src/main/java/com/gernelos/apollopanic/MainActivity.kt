package com.gernelos.apollopanic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFD0BCFF),
                onPrimary = Color(0xFF381E72),
                background = Color(0xFF111318),
                surface = Color(0xFF191C20),
                onSurface = Color(0xFFE2E2E9)
            )) {
                val scope = rememberCoroutineScope()
                var status by remember { mutableStateOf("Ready") }
                var busy by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(Modifier.background(Color(0xFF292333), CircleShape).padding(22.dp)) {
                            Text("A", color = Color(0xFFD0BCFF), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(22.dp))
                        Text("Apollo Panic", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("Alcatel 9032X • Apollo_8_4G", color = Color(0xFFBFC2CC), fontSize = 13.sp)
                        Spacer(Modifier.height(42.dp))
                        Button(
                            onClick = {
                                if (!busy) {
                                    busy = true
                                    status = "Requesting root access…"
                                    scope.launch {
                                        status = withContext(Dispatchers.IO) { triggerPanic() }
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD0BCFF),
                                contentColor = Color(0xFF381E72),
                                disabledContainerColor = Color(0xFF494451)
                            )
                        ) {
                            Text("Panic Now", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(22.dp))
                        Text(status, color = Color(0xFFCAC4D0), fontSize = 14.sp)
                        Spacer(Modifier.height(36.dp))
                        Text(
                            "This action intentionally crashes the kernel and forces a reboot.",
                            color = Color(0xFF92939B),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }

    private fun triggerPanic(): String {
        val checkScript = """
            if [ "$(id -u)" != "0" ]; then
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
        if (check.exitCode != 0 || !check.output.contains("READY")) {
            return when {
                check.output.contains("NO_ROOT") -> "Root permission not granted"
                check.output.contains("UNSUPPORTED") -> "Kernel SysRq panic is not supported"
                check.output.contains("NOT_WRITABLE") -> "SysRq trigger is not writable"
                else -> "Root check failed: " + check.output.ifBlank { "unknown error" }
            }
        }

        runRootCommand("echo c > /proc/sysrq-trigger")
        return "Panic command returned without crashing"
    }

    private data class RootResult(val exitCode: Int, val output: String)

    private fun runRootCommand(command: String): RootResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            RootResult(process.waitFor(), output)
        } catch (error: Exception) {
            RootResult(-1, error.message ?: "su unavailable")
        }
    }
}
