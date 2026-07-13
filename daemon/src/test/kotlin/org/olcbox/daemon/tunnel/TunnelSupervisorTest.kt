package org.olcbox.daemon.tunnel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// TunnelSupervisor's start()/stop() flow spawns real `kill`/`ip` processes
// and expects bundled olcRTC/hev-socks5-tunnel binaries (Stage 4) plus
// CAP_NET_ADMIN — none of that is available in the Docker build container,
// so it can't be exercised as a hermetic unit test. This file covers the
// one piece of pure logic the class exposes; the actual start/stop/EBUSY-fix
// behavior needs the manual on-host test described in the plan's
// verification section.
class TunnelSupervisorTest {

    @Test
    fun detectsFailedToConnectLink() {
        assertTrue(TunnelSupervisor.isFatalOlcRtcStartupLine("2026/07/11 failed to connect link: timeout"))
    }

    @Test
    fun detectsJoinRoomFailed() {
        assertTrue(TunnelSupervisor.isFatalOlcRtcStartupLine("join room failed: room-1"))
    }

    @Test
    fun detectsGetRoomTokenFailed() {
        assertTrue(TunnelSupervisor.isFatalOlcRtcStartupLine("get room token: request failed"))
    }

    @Test
    fun detectsTransportConnectFailed() {
        assertTrue(TunnelSupervisor.isFatalOlcRtcStartupLine("transport connect vp8channel failed"))
    }

    @Test
    fun isCaseInsensitive() {
        assertTrue(TunnelSupervisor.isFatalOlcRtcStartupLine("JOIN ROOM FAILED"))
    }

    @Test
    fun ignoresBenignLines() {
        assertFalse(TunnelSupervisor.isFatalOlcRtcStartupLine("SOCKS5 server listening on 127.0.0.1:10809"))
        assertFalse(TunnelSupervisor.isFatalOlcRtcStartupLine("session abc123 opened (device=xyz)"))
    }

    @Test
    fun requiresBothTermsForPartialMatches() {
        assertFalse(TunnelSupervisor.isFatalOlcRtcStartupLine("get room token succeeded"))
        assertFalse(TunnelSupervisor.isFatalOlcRtcStartupLine("transport connect vp8channel started"))
    }
}
