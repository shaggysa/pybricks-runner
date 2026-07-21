package xyz.shaggysa

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import com.pty4j.PtyProcessBuilder
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

private val LOG = Logger.getInstance("PybricksRunner")

@OptIn(ExperimentalSerializationApi::class)
val serializer = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    explicitNulls = false
    classDiscriminator = "event_type"
}

@Serializable
sealed class IncomingEvent {
    @Serializable @SerialName("ble_device_found")
    data class BleDeviceFound(val deviceName: String, val address: String, val rssi: Int) : IncomingEvent()

    @Serializable @SerialName("hub_connected")
    object HubConnected : IncomingEvent()

    @Serializable @SerialName("connection_timeout")
    object ConnectionTimeout : IncomingEvent()

    @Serializable @SerialName("download_progress_update")
    data class DownloadProgressUpdate(val percentage: Double) : IncomingEvent()

    @Serializable @SerialName("program_started")
    object ProgramStarted : IncomingEvent()

    @Serializable @SerialName("program_complete")
    object ProgramComplete : IncomingEvent()

    @Serializable @SerialName("hub_printed_line")
    data class HubPrintedLine(val line: String) : IncomingEvent()

    @Serializable @SerialName("compile_error")
    data class CompileError(val traceback: String) : IncomingEvent()

    @Serializable @SerialName("hub_firmware_outdated")
    data class HubFirmwareOutdated(val explanation: String) : IncomingEvent()

    @Serializable @SerialName("precondition_violated")
    data class PreconditionViolated(val explanation: String) : IncomingEvent()

    @Serializable @SerialName("hub_disconnected")
    object HubDisconnected : IncomingEvent()
}

enum class ConnectionType {
    @SerialName("ble") Bluetooth,
    @SerialName("usb") Usb
}

@Serializable
sealed class OutgoingEvent {
    @Serializable @SerialName("start_ble_scanning")
    data class StartBleScanning(val timeout: Double) : OutgoingEvent()

    @Serializable @SerialName("connect_to_hub")
    data class ConnectToHub(val connType: ConnectionType, val bleAddress: String) : OutgoingEvent()

    @Serializable @SerialName("disconnect_from_hub")
    object DisconnectFromHub : OutgoingEvent()

    @Serializable @SerialName("recompile_download")
    data class RecompileDownload(val programPath: String) : OutgoingEvent()

    @Serializable @SerialName("recompile_run")
    data class RecompileRun(val programPath: String) : OutgoingEvent()

    @Serializable @SerialName("run_stored")
    object RunStored : OutgoingEvent()

    @Serializable @SerialName("cancel_running_program")
    object CancelRunningProgram : OutgoingEvent()

    @Serializable @SerialName("exit")
    object Exit: OutgoingEvent()
}

fun OutputStream.sendEvent(event: OutgoingEvent, proc: Process?) {
    val encodedEvent = serializer.encodeToString<OutgoingEvent>(event)
    val bytes = (encodedEvent + "\n").toByteArray(Charsets.UTF_8)
    try {
        this.write(bytes)
        this.flush()
        LOG.info("Sent an event to brickpipe: $encodedEvent")
    } catch (e: IOException) {
        throw e
    }
}

class PyState(val uvxPath: String) {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    
    @Volatile
    var isRunning = false
        private set

    private val eventListeners = mutableListOf<(IncomingEvent) -> Unit>()
    private val errorListeners = CopyOnWriteArrayList<(Throwable) -> Unit>()
    private val terminationListeners = CopyOnWriteArrayList<(Int) -> Unit>()

    fun addEventListener(listener: (IncomingEvent) -> Unit) {
        synchronized(eventListeners) { eventListeners.add(listener) }
    }

    fun removeEventListener(listener: (IncomingEvent) -> Unit) {
        synchronized(eventListeners) { eventListeners.remove(listener) }
    }

    fun addErrorListener(listener: (Throwable) -> Unit) {
        errorListeners.add(listener)
    }

    fun addTerminationListener(listener: (Int) -> Unit) {
        terminationListeners.add(listener)
    }


    @Synchronized
    fun start() {
        if (isRunning) return
        try {
            LOG.info("Starting brickpipe via $uvxPath")
            val isWindows = System.getProperty("os.name").lowercase().contains("win")

            // mpy-cross freezes on windows unless it has a PTY
            val proc: Process = if (isWindows) {
                PtyProcessBuilder(arrayOf(uvxPath, "-q", "brickpipe@0.4.0"))
                    .setInitialColumns(200)
                    .setInitialRows(50)
                    .setConsole(true)
                    .start()

            } else {
                ProcessBuilder(uvxPath, "brickpipe@0.4.0").start()
            }
            process = proc
            reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
            isRunning = true

            Thread {
                readLoop()
            }.apply {
                name = "brickpipe-stdout-reader"
                isDaemon = true
                start()
            }

            Thread {
                try {
                    val errReader = proc.errorStream.bufferedReader()
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        LOG.warn("brickpipe stderr: $line")
                    }
                } catch (e: IOException) {
                }
            }.apply {
                name = "brickpipe-stderr-reader"
                isDaemon = true
                start()
            }

            Thread {
                try {
                    val exitCode = proc.waitFor()
                    isRunning = false
                    LOG.info("brickpipe process terminated with exit code $exitCode")
                    terminationListeners.forEach { it(exitCode) }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.apply {
                name = "brickpipe-watcher"
                isDaemon = true
                start()
            }

        } catch (e: Exception) {
            isRunning = false
            LOG.error("Failed to start brickpipe process", e)
            errorListeners.forEach { it(e) }
            throw e
        }
    }

    private fun readLoop() {
        LOG.info("brickpipe stdout reader started")
        val currentReader = reader ?: return
        try {
            var line: String? = null
            while (isRunning && currentReader.readLine().also { line = it } != null) {
                val lineStr = line ?: break
                if (lineStr.isEmpty()) continue
                LOG.info("Received line from brickpipe: $lineStr")
                try {
                    val event = serializer.decodeFromString<IncomingEvent>(lineStr)
                    synchronized(eventListeners) {
                        eventListeners.forEach { it(event) }
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to deserialize incoming event: $lineStr", e)
                }
            }
        } catch (e: IOException) {
            LOG.warn("brickpipe stdout reader stopped with exception", e)
        } finally {
            isRunning = false
        }
    }

    @Synchronized
    fun sendEvent(event: OutgoingEvent) {
        val currentProcess = process
        if (currentProcess == null || !isRunning) {
            throw IOException("brickpipe process is not running")
        }
        currentProcess.outputStream.sendEvent(event, currentProcess)
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        LOG.info("Stopping brickpipe process")
        try {
            sendEvent(OutgoingEvent.Exit)
        } catch (e: Exception) {
            // ignore failure to send Exit clean command
        }
        process?.waitFor()
        process = null
        reader = null
    }
}

@Service(Service.Level.PROJECT)
class PyStateService(val project: Project) {
    var pyState: PyState? = null
        private set

    @Synchronized
    fun getOrCreatePyState(uvxPath: String): PyState {
        val current = pyState
        if (current != null && current.isRunning) {
            return current
        }
        current?.stop()
        val newState = PyState(uvxPath)
        pyState = newState
        return newState
    }

    @Synchronized
    fun stopService() {
        pyState?.stop()
        pyState = null
    }
}

class SendEventAction() : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = project.getService(PyStateService::class.java)
        val activeState = service?.pyState
        if (activeState == null || !activeState.isRunning) {
            println("SendEventAction: PyState process is not running.")
            return
        }
        // Send a scan event as a safe placeholder action
        try {
            activeState.sendEvent(OutgoingEvent.StartBleScanning(5.0))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
