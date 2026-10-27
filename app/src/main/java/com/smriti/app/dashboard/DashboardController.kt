package com.smriti.app.dashboard

import com.smriti.app.AppContainer
import com.smriti.app.pipeline.PipelineBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.BindException
import java.net.NetworkInterface
import kotlin.random.Random

data class DashboardInfo(
    val running: Boolean = false,
    val lan: Boolean = false,
    val port: Int = DashboardController.PORT,
    val pin: String = "",
    val clients: Int = 0,
    val addresses: List<String> = emptyList(),
    val error: String? = null
)

/** Starts and stops the dashboard server to match the Settings switches, and tells the UI what to show. */
class DashboardController(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: DashboardServer? = null
    private var poll: Job? = null

    private val _info = MutableStateFlow(DashboardInfo())
    val info: StateFlow<DashboardInfo> = _info.asStateFlow()

    @Synchronized
    fun apply(on: Boolean, lan: Boolean) {
        val current = _info.value
        if (on == current.running && (!on || lan == current.lan)) return
        stopServer()
        if (!on) { _info.value = DashboardInfo(); return }

        val pin = Random.nextInt(100_000, 999_999).toString()
        try {
            val s = DashboardServer(container.app, container, PORT, lan, pin)
            s.begin()
            server = s
            _info.value = DashboardInfo(running = true, lan = lan, pin = pin, addresses = if (lan) localAddresses() else emptyList())
            poll = scope.launch {
                while (true) {
                    _info.value = _info.value.copy(clients = s.clientCount, addresses = if (lan) localAddresses() else emptyList())
                    delay(2000)
                }
            }
        } catch (e: BindException) {
            _info.value = DashboardInfo(error = "Port $PORT is already in use")
            PipelineBus.error("dashboard", "bind failed: ${e.message}")
        } catch (e: Throwable) {
            _info.value = DashboardInfo(error = e.message ?: e.javaClass.simpleName)
            PipelineBus.error("dashboard", "start failed: ${e.message}")
        }
    }

    private fun stopServer() {
        poll?.cancel(); poll = null
        server?.end(); server = null
    }

    /** Private IPv4 addresses of this phone, for the "open this on your laptop" hint in LAN mode. */
    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it.isSiteLocalAddress && it.hostAddress?.contains(':') == false }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    companion object {
        const val PORT = 8765
    }
}
