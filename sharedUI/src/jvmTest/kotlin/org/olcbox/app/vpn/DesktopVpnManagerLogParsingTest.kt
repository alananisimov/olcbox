package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The room list is refreshed off this line, and on a whitelist that refresh is
 * the only thing standing between a handover and a client whose stored rooms
 * have all been retired. Worth pinning to real olcRTC output.
 */
class DesktopVpnManagerLogParsingTest {

    @Test
    fun recognisesTheSessionOpenedLineOlcRtcActuallyPrints() {
        assertTrue(
            isSessionOpenedLine(
                "2026/08/28 22:06:57 session 4238c0b2-46e3-4234-b7ac-a90284ee079f " +
                    "opened (device=ffb3ad78-e7e5-4342-ade1-ffdfbbf7bc33)"
            )
        )
    }

    @Test
    fun ignoresNeighbouringLinesThatAlsoMentionSessions() {
        val notASessionOpen = listOf(
            "2026/08/28 22:06:53 control stream ended role=client session=e19b11d8: control stream closed by peer",
            "2026/08/28 22:06:53 client reconnect reason=liveness - tearing down smux session",
            "2026/08/28 22:06:55 failover cycle=1 starting profile=81055221156696 provider=telemost",
            "2026/08/28 22:06:57 SOCKS5 server listening on 127.0.0.1:10808",
            "2026/08/28 22:06:57 session closed: id=4238c0b2 reason=closed",
        )
        for (line in notASessionOpen) {
            assertFalse(isSessionOpenedLine(line), line)
        }
    }
}
