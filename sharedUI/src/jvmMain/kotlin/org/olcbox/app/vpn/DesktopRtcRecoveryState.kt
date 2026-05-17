package org.olcbox.app.vpn

internal data class DesktopRecoveryRequest(
    val reason: String,
    val fullRestart: Boolean
)

internal class DesktopRtcRecoveryState(
    private val startupGraceMs: Long,
    private val failureWindowMs: Long,
    private val trafficProbeThreshold: Int
) {
    private var lastConnectedAtMs: Long = 0L
    private var lastRtcFailureAtMs: Long = 0L
    private var rtcFailureCount: Int = 0
    private var trafficProbeFailures: Int = 0

    fun markConnected(nowMs: Long = System.currentTimeMillis()) {
        lastConnectedAtMs = nowMs
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
        trafficProbeFailures = 0
    }

    fun noteRtcFailure(
        failure: RtcLogEvent.Failure,
        nowMs: Long = System.currentTimeMillis()
    ): DesktopRecoveryRequest? {
        if (nowMs - lastConnectedAtMs < startupGraceMs) return null

        rtcFailureCount = if (nowMs - lastRtcFailureAtMs <= failureWindowMs) {
            rtcFailureCount + 1
        } else {
            1
        }
        lastRtcFailureAtMs = nowMs

        return if (rtcFailureCount >= failure.threshold) {
            DesktopRecoveryRequest(failure.reason, failure.recreateTunnel)
        } else {
            null
        }
    }

    fun noteTrafficProbe(success: Boolean): DesktopRecoveryRequest? {
        if (success) {
            trafficProbeFailures = 0
            return null
        }

        trafficProbeFailures += 1
        return if (trafficProbeFailures >= trafficProbeThreshold) {
            DesktopRecoveryRequest("Desktop traffic probe failed", fullRestart = false)
        } else {
            null
        }
    }
}
