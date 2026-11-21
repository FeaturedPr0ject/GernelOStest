package com.gernelos.apollopanic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.io.File

private val AppBackground = Color(0xFF101318)
private val AppSurface = Color(0xFF1B2028)
private val AppPrimary = Color(0xFFB8C7FF)
private val AppOnPrimary = Color(0xFF182D60)
private val AppText = Color(0xFFE1E5F0)
private val AppMuted = Color(0xFFAEB6C5)

class MainActivity : ComponentActivity() {
    private val uidFile: File by lazy { File(filesDir, "systemui_uid.tmp") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = darkColorScheme(
                primary = AppPrimary,
                onPrimary = AppOnPrimary,
                background = AppBackground,
                surface = AppSurface,
                onSurface = AppText,
                secondary = Color(0xFFADCBA8)
            )
            MaterialTheme(colorScheme = colors) {
                RootPanicApp(
                    uidFile = uidFile,
                    onKernelPanic = { runRootAction(::triggerPanic) },
                    onKillSystemUi = { runRootAction(::killSystemUi) }
                )
            }
        }
    }

    private suspend fun runRootAction(action: () -> String): String =
        withContext(Dispatchers.IO) { action() }

    private fun triggerPanic(): String {
        val check = runRootCommand("if [ \"$(id -u)\" = \"0\" ]; then echo ROOT_OK; else echo NO_ROOT; fi")
        if (!check.output.contains("ROOT_OK")) return "Root permission not granted"
        val command = runRootCommand("echo c > /proc/sysrq-trigger")
        return if (command.exitCode == 0) "Panic command returned without crashing" else "Panic command failed: ${command.output}"
    }

    private fun killSystemUi(): String {
        val rootCheck = runRootCommand("if [ \"$(id -u)\" = \"0\" ]; then echo ROOT_OK; else echo NO_ROOT; fi")
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
            pids=$(ps -A -o UID,PID,NAME | awk -v uid="${'$'}saved_uid" '$1 == uid && $3 ~ /^com[.]android[.]systemui(:.*)?$/ {print $2}')
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
                "SystemUI stopped. UID ${'$'}uid saved temporarily."
            }
            result.output.contains("SYSTEMUI_UID_NOT_FOUND") -> "Could not find the SystemUI UID"
            result.output.contains("UID_TEMP_FILE_FAILED") -> "Could not save the temporary UID file"
            result.output.contains("SYSTEMUI_PROCESS_NOT_FOUND") -> "SystemUI process was not found for that UID"
            else -> "SystemUI action failed: ${result.output.ifBlank { "unknown error" }}"
        }
    }

    private data class RootResult(val exitCode: Int, val output: String)

    private fun runRootCommand(command: String): RootResult = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        RootResult(process.waitFor(), output)
    } catch (error: Exception) {
        RootResult(-1, error.message ?: "su unavailable")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootPanicApp(
    uidFile: File,
    onKernelPanic: suspend () -> String,
    onKillSystemUi: suspend () -> String
) {
    var selectedPage by remember { mutableStateOf("Kernel Panic") }
    var menuExpanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var busy by remember { mutableStateOf(false) }
    var savedUid by remember { mutableStateOf(uidFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()) }
    val scope = rememberCoroutineScope()

    fun perform(action: suspend () -> String) {
        if (busy) return
        busy = true
        status = "Requesting root access…"
        scope.launch {
            status = action()
            savedUid = uidFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
            busy = false
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Root Panic", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                        Text("Root tools", color = AppMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Text("☰", color = AppText, fontSize = 24.sp)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            containerColor = AppSurface
                        ) {
                            DropdownMenuItem(
                                text = { Text("Kernel Panic") },
                                onClick = {
                                    selectedPage = "Kernel Panic"
                                    status = "Ready"
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Kill SystemUI") },
                                onClick = {
                                    selectedPage = "Kill SystemUI"
                                    status = "Ready"
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF202B43),
                    titleContentColor = AppText
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(selectedPage, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = AppText)
            if (selectedPage == "Kernel Panic") {
                Text(
                    "Force a kernel crash using the Linux SysRq trigger.",
                    color = AppMuted,
                    fontSize = 14.sp
                )
                Button(
                    onClick = { perform(onKernelPanic) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(58.dp)
                ) {
                    Text("Panic Now", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Warning: this immediately crashes the kernel and reboots the device. Unsaved data may be lost.",
                    color = Color(0xFFE6B9B9),
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            } else {
                Text(
                    "Find the SystemUI UID, save it to a temporary file, then stop only processes matching both that UID and the SystemUI process name.",
                    color = AppMuted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
                Button(
                    onClick = { perform(onKillSystemUi) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(58.dp)
                ) {
                    Text("Kill SystemUI", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Temporary UID", fontWeight = FontWeight.SemiBold, color = AppText)
                        Text(savedUid.ifBlank { "No UID saved" }, color = AppMuted, fontSize = 14.sp)
                        Text(
                            "The temporary file is cleared automatically at the next boot.",
                            color = AppMuted,
                            fontSize = 12.sp
                        )
                        OutlinedButton(
                            onClick = {
                                uidFile.delete()
                                savedUid = ""
                                status = "Temporary UID deleted"
                            },
                            enabled = savedUid.isNotBlank()
                        ) {
                            Text("Delete temporary UID")
                        }
                    }
                }
                Text(
                    "SystemUI may restart automatically. Only matching SystemUI processes are targeted; other processes sharing the same UID are left alone.",
                    color = Color(0xFFE6D6A8),
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = AppSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status", color = AppMuted, fontSize = 12.sp)
                    Text(status, color = AppText, fontSize = 14.sp)
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
