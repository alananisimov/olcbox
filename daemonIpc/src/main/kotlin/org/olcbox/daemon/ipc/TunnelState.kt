package org.olcbox.daemon.ipc

// String values used in TunnelStatusResponse.state — shared so neither the
// daemon (producer) nor the client (consumer) hand-duplicates the literals.
object TunnelState {
    const val STOPPED = "stopped"
    const val STARTING = "starting"
    const val CONNECTED = "connected"
    const val ERROR = "error"
}
