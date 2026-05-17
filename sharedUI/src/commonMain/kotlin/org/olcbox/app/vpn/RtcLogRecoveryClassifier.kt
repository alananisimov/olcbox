package org.olcbox.app.vpn

internal sealed interface RtcLogEvent {
    data object Connected : RtcLogEvent

    data class Failure(
        val reason: String,
        val recreateTunnel: Boolean,
        val threshold: Int
    ) : RtcLogEvent
}

internal object RtcLogRecoveryClassifier {
    fun classify(line: String): RtcLogEvent? {
        val lowerLine = line.lowercase()

        if (lowerLine.contains("ice connection state changed: connected") ||
            lowerLine.contains("peer connection state changed: connected") ||
            lowerLine.contains("socks5 server listening") ||
            lowerLine.contains("link connected") ||
            lowerLine.contains("session opened")
        ) {
            return RtcLogEvent.Connected
        }

        if (lowerLine.contains("session closed")) {
            return RtcLogEvent.Failure(
                reason = "RTC session closed",
                recreateTunnel = true,
                threshold = 1
            )
        }

        if (lowerLine.contains("conference end") ||
            lowerLine.contains("conference ended") ||
            lowerLine.contains("jitsi bridge closed")
        ) {
            return RtcLogEvent.Failure(
                reason = "RTC conference ended",
                recreateTunnel = true,
                threshold = 1
            )
        }

        if (lowerLine.contains("read welcome") ||
            lowerLine.contains("unexpected handshake message") ||
            lowerLine.contains("control_ping")
        ) {
            return RtcLogEvent.Failure(
                reason = "RTC handshake failed",
                recreateTunnel = false,
                threshold = 1
            )
        }

        if (lowerLine.contains("control stream unhealthy")) {
            return RtcLogEvent.Failure(
                reason = "RTC control stream unhealthy",
                recreateTunnel = false,
                threshold = 1
            )
        }

        if (lowerLine.contains("server reconnect")) {
            return RtcLogEvent.Failure(
                reason = "RTC server requested reconnect",
                recreateTunnel = false,
                threshold = 1
            )
        }

        if (lowerLine.contains("control missed pong") ||
            lowerLine.contains("missed_pongs")
        ) {
            return RtcLogEvent.Failure(
                reason = "RTC liveness missed pong",
                recreateTunnel = false,
                threshold = 2
            )
        }

        if (lowerLine.contains("ice connection state changed: failed") ||
            lowerLine.contains("peer connection state changed: failed")
        ) {
            return RtcLogEvent.Failure(
                reason = "RTC failed",
                recreateTunnel = true,
                threshold = 1
            )
        }

        if (lowerLine.contains("ice connection state changed: closed") ||
            lowerLine.contains("peer connection state changed: closed")
        ) {
            return RtcLogEvent.Failure(
                reason = "RTC closed",
                recreateTunnel = true,
                threshold = 2
            )
        }

        if (lowerLine.contains("network is unreachable") ||
            lowerLine.contains("use of closed network connection") ||
            lowerLine.contains("read/write on closed pipe") ||
            lowerLine.contains("remote not ready") ||
            lowerLine.contains("read_err=eof")
        ) {
            return RtcLogEvent.Failure(
                reason = "RTC network path is closed",
                recreateTunnel = false,
                threshold = 3
            )
        }

        return null
    }
}
