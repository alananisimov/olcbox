package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RtcLogRecoveryClassifierTest {

    @Test
    fun marksLinkConnectedAsHealthy() {
        assertEquals(
            RtcLogEvent.Connected,
            RtcLogRecoveryClassifier.classify("Link connected")
        )
    }

    @Test
    fun treatsSessionClosedAsImmediateTunnelRecovery() {
        assertEquals(
            RtcLogEvent.Failure(
                reason = "RTC session closed",
                recreateTunnel = true,
                threshold = 1
            ),
            RtcLogRecoveryClassifier.classify(
                "session closed: id=444b68b7-4504-4644-a93e-88c12210d537 reason=closed"
            )
        )
    }

    @Test
    fun treatsControlMissedPongAsLivenessFailure() {
        assertEquals(
            RtcLogEvent.Failure(
                reason = "RTC liveness missed pong",
                recreateTunnel = false,
                threshold = 2
            ),
            RtcLogRecoveryClassifier.classify("control missed pong on server: missed_pongs=1")
        )
    }

    @Test
    fun treatsControlUnhealthyAsImmediateRecovery() {
        assertEquals(
            RtcLogEvent.Failure(
                reason = "RTC control stream unhealthy",
                recreateTunnel = false,
                threshold = 1
            ),
            RtcLogRecoveryClassifier.classify("control stream unhealthy on server: missed_pongs=3")
        )
    }

    @Test
    fun treatsConferenceEndAsImmediateTunnelRecovery() {
        assertEquals(
            RtcLogEvent.Failure(
                reason = "RTC conference ended",
                recreateTunnel = true,
                threshold = 1
            ),
            RtcLogRecoveryClassifier.classify("Client link reported conference end: jitsi bridge closed")
        )
    }

    @Test
    fun treatsHandshakeTimeoutAsRecoverableTransportFailure() {
        assertEquals(
            RtcLogEvent.Failure(
                reason = "RTC handshake failed",
                recreateTunnel = false,
                threshold = 1
            ),
            RtcLogRecoveryClassifier.classify("handshake client: read welcome: read hdr: timeout")
        )
    }

    @Test
    fun treatsUnexpectedControlPingAsRecoverableTransportFailure() {
        assertEquals(
            RtcLogEvent.Failure(
                reason = "RTC handshake failed",
                recreateTunnel = false,
                threshold = 1
            ),
            RtcLogRecoveryClassifier.classify("unexpected handshake message: got \"CONTROL_PING\"")
        )
    }

    @Test
    fun ignoresTrafficLines() {
        assertNull(
            RtcLogRecoveryClassifier.classify(
                "traffic: session=abc addr=example.com:443 in=100 out=200"
            )
        )
    }
}
