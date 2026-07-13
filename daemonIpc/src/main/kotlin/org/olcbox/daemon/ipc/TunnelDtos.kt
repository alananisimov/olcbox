package org.olcbox.daemon.ipc

import kotlinx.serialization.Serializable

// The client already has the full LocationConfig + OlcRtcCommand.yaml()
// logic (:daemon cannot depend on :sharedUI), so it renders the complete
// olcrtc config itself and hands the daemon a ready string — the daemon
// stays dumb about provider/transport semantics, it only writes files and
// manages process lifecycle. socksPort is kept separate (not parsed out of
// the YAML) so the daemon can wire hev-socks5-tunnel to the same port
// without any YAML parsing/mutation.
@Serializable
data class StartTunnelRequest(
    val olcRtcConfigYaml: String,
    val socksPort: Int
)

@Serializable
data class TunnelStatusResponse(
    val state: String,
    val message: String? = null
)

@Serializable
data class LogEntry(
    val cursor: Long,
    val source: String,
    val line: String
)

@Serializable
data class LogsResponse(
    val entries: List<LogEntry>,
    val nextCursor: Long
)
