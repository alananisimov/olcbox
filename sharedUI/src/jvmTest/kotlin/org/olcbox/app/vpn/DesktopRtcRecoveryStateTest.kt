package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopRtcRecoveryStateTest {

    @Test
    fun ignoresRtcFailuresDuringStartupGrace() {
        val state = DesktopRtcRecoveryState(
            startupGraceMs = 2_500,
            failureWindowMs = 6_000,
            trafficProbeThreshold = 2
        )

        state.markConnected(nowMs = 10_000)

        assertNull(
            state.noteRtcFailure(
                RtcLogEvent.Failure("RTC closed", recreateTunnel = true, threshold = 1),
                nowMs = 11_000
            )
        )
    }

    @Test
    fun requestsRecoveryWhenRtcFailureThresholdIsReached() {
        val state = DesktopRtcRecoveryState(
            startupGraceMs = 0,
            failureWindowMs = 6_000,
            trafficProbeThreshold = 2
        )

        state.markConnected(nowMs = 10_000)

        assertNull(
            state.noteRtcFailure(
                RtcLogEvent.Failure("RTC liveness missed pong", recreateTunnel = false, threshold = 2),
                nowMs = 13_000
            )
        )

        assertEquals(
            DesktopRecoveryRequest("RTC liveness missed pong", fullRestart = false),
            state.noteRtcFailure(
                RtcLogEvent.Failure("RTC liveness missed pong", recreateTunnel = false, threshold = 2),
                nowMs = 14_000
            )
        )
    }

    @Test
    fun trafficProbeRequestsRecoveryAfterConsecutiveFailures() {
        val state = DesktopRtcRecoveryState(
            startupGraceMs = 0,
            failureWindowMs = 6_000,
            trafficProbeThreshold = 2
        )

        state.markConnected(nowMs = 10_000)

        assertNull(state.noteTrafficProbe(success = false))
        assertEquals(
            DesktopRecoveryRequest("Desktop traffic probe failed", fullRestart = false),
            state.noteTrafficProbe(success = false)
        )
    }

    @Test
    fun trafficProbeSuccessClearsFailureCount() {
        val state = DesktopRtcRecoveryState(
            startupGraceMs = 0,
            failureWindowMs = 6_000,
            trafficProbeThreshold = 2
        )

        state.markConnected(nowMs = 10_000)

        assertNull(state.noteTrafficProbe(success = false))
        assertNull(state.noteTrafficProbe(success = true))
        assertNull(state.noteTrafficProbe(success = false))
    }

    @Test
    fun markConnectedAllowsNewRecoveryAfterPreviousRequest() {
        val state = DesktopRtcRecoveryState(
            startupGraceMs = 0,
            failureWindowMs = 6_000,
            trafficProbeThreshold = 2
        )

        state.markConnected(nowMs = 10_000)

        assertEquals(
            DesktopRecoveryRequest("Desktop traffic probe failed", fullRestart = false),
            state.noteTrafficProbe(success = false) ?: state.noteTrafficProbe(success = false)
        )

        state.markConnected(nowMs = 20_000)

        assertNull(state.noteTrafficProbe(success = false))
        assertEquals(
            DesktopRecoveryRequest("Desktop traffic probe failed", fullRestart = false),
            state.noteTrafficProbe(success = false)
        )
    }
}
