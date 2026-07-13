package org.olcbox.app.vpn.desktop.daemon

import co.touchlab.kermit.Logger
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.olcbox.app.desktop.DesktopPaths
import org.olcbox.daemon.ipc.DaemonPaths
import org.olcbox.daemon.ipc.StartTunnelRequest
import org.olcbox.daemon.ipc.TunnelState

// Successor to LinuxTunController's role, but talks to the persistent
// olcbox-daemon over IPC instead of directly spawning pkexec-wrapped
// processes. Owns background log/status polling for the lifetime of a
// connection; onUnexpectedStop mirrors the old process-exit-watcher
// callback, letting DesktopVpnManager react the same way it always has to
// an unrequested disconnect.
internal class LinuxDaemonTunController(
    private val addLog: (String) -> Unit,
    private val scope: CoroutineScope,
    private val onUnexpectedStop: (String) -> Unit
) {
    private val client = DaemonIpcClient(
        socketPath = Path.of(DaemonPaths.SOCKET_PATH),
        keyDir = DesktopPaths.appDataDir()
    )

    private var pollJob: Job? = null
    private var logCursor = 0L

    @Volatile
    private var expectingConnected = false

    suspend fun start(olcRtcConfigYaml: String, socksPort: Int) {
        logCursor = 0L
        val status = client.startTunnel(StartTunnelRequest(olcRtcConfigYaml, socksPort))
        if (status.state != TunnelState.CONNECTED) {
            error(status.message ?: "olcbox-daemon failed to start the tunnel (state=${status.state})")
        }
        expectingConnected = true
        startPolling()
    }

    suspend fun stop() {
        expectingConnected = false
        pollJob?.cancel()
        pollJob = null
        runCatching { client.stopTunnel() }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                runCatching {
                    val logs = client.logsSince(logCursor)
                    for (entry in logs.entries) {
                        addLog("${entry.source}: ${entry.line}")
                    }
                    logCursor = logs.nextCursor

                    if (expectingConnected) {
                        val current = client.status()
                        if (current.state != TunnelState.CONNECTED) {
                            expectingConnected = false
                            onUnexpectedStop(
                                current.message ?: "olcbox-daemon reported state=${current.state}"
                            )
                        }
                    }
                }.onFailure { e ->
                    // Previously silent — a poll tick failing (e.g. the
                    // socket became briefly unreachable) left no trace
                    // anywhere, including here in adb logcat / desktop
                    // console, making exactly this kind of issue invisible.
                    Logger.w(e, tag = TAG) { "poll tick failed" }
                }
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
        const val TAG = "LinuxDaemonTunController"
    }
}
