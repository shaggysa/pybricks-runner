package xyz.shaggysa

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.io.BufferedWriter

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
    data class ConnectToHub(val connType: ConnectionType, val hubName: String?) : OutgoingEvent()

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

fun BufferedWriter.sendEvent(event: OutgoingEvent) {
    val encodedEvent = serializer.encodeToString<OutgoingEvent>(event)
    println("wrote event: $encodedEvent")
    try {
        this.write(encodedEvent)
        this.newLine()
        this.flush()
    } catch (e: java.io.IOException) {
        e.printStackTrace()
    }

}

class PyState(uvxPath: String) {
    val brickpipe: Process = ProcessBuilder(uvxPath, "brickpipe@0.3.1").start()
    val reader = brickpipe.inputStream.bufferedReader()
    val writer = brickpipe.outputStream.bufferedWriter()
}

class SendEventAction() : AnAction() {
    override fun actionPerformed(p0: AnActionEvent) {
        TODO("Not yet implemented")
    }
}
