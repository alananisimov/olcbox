package org.olcbox.daemon.ipc

// Shared between client (:sharedUI, to connect + build config paths) and
// server (:daemon, to install itself into these exact locations) — living
// in :daemonIpc so neither side can drift from the other by hand-duplicating
// these strings independently.
object DaemonPaths {
    const val INSTALL_DIR = "/usr/local/lib/olcbox-daemon"
    const val BIN_DIR = "$INSTALL_DIR/bin"
    const val DATA_DIR = "$INSTALL_DIR/data"
    const val RUNTIME_DIR = "/run/olcbox-daemon"
    const val SOCKET_PATH = "$RUNTIME_DIR/daemon.sock"

    // Holds the per-run olcRTC/hev config (contains the room key) — kept as a
    // subdirectory, locked to 0700, separate from RUNTIME_DIR itself, which
    // must stay traversable (systemd's default 0755) for unprivileged
    // clients to reach the socket. Locking RUNTIME_DIR itself was the
    // original bug: it also blocked the socket, since Unix socket connect()
    // requires execute permission on every parent directory in the path.
    const val CONFIG_DIR = "$RUNTIME_DIR/config"
}
