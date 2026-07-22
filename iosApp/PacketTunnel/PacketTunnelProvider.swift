import Darwin
import Foundation
import NetworkExtension
import OlcRtcMobile
import HevSocks5Tunnel
import os

/// Full-tunnel (VPN) mode for iOS.
///
/// This is the iOS analogue of Android's `OlcboxVpnService`. The whole data path
/// lives inside this Network Extension process so that it keeps running while the
/// host app is suspended (which is exactly what the proxy mode's silent-audio hack
/// works around, and what a real VPN must not depend on):
///
///   device packets -> utun fd -> hev-socks5-tunnel (lwIP tun2socks)
///                  -> 127.0.0.1:socksPort (olcRTC local SOCKS5, hosted here)
///                  -> olcRTC WebRTC relay -> internet
///
/// The olcRTC engine (`OlcRtcMobile`, gomobile) opens the local SOCKS5 listener in
/// this process; hev-socks5-tunnel converts the utun IP stream into SOCKS5 sessions
/// against that listener. Config (carrier/room/key/socks creds) is handed over by
/// the app through `protocolConfiguration.providerConfiguration`.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let log = OSLog(subsystem: "org.olcbox.app.ios.PacketTunnel", category: "tunnel")
    private let hevQueue = DispatchQueue(label: "org.olcbox.packettunnel.hev")
    // Both flags are touched from the provider's calling thread (start/stopTunnel)
    // and from hevQueue, so every access goes through stateLock.
    private let stateLock = NSLock()
    private var hevRunning = false
    private var stopped = false

    // MARK: NEPacketTunnelProvider

    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        let config: TunnelConfig
        do {
            config = try TunnelConfig(protocolConfiguration: protocolConfiguration)
        } catch {
            os_log("invalid tunnel configuration: %{public}@", log: log, type: .error, "\(error)")
            completionHandler(error)
            return
        }

        os_log("starting olcRTC SOCKS in-extension (carrier=%{public}@ transport=%{public}@ port=%d)",
               log: log, type: .info, config.carrierName, config.transportName, config.socksPort)

        // 1. Start the olcRTC engine's local SOCKS5 listener inside this process.
        if let error = startOlcRtc(config) {
            os_log("olcRTC start failed: %{public}@", log: log, type: .error, "\(error)")
            completionHandler(error)
            return
        }

        // 2. Configure the utun interface (address, DNS, default route). iOS owns the
        //    interface addressing here (unlike Android/macOS where hev sets it), so the
        //    hev YAML only needs MTU + SOCKS upstream.
        let settings = makeNetworkSettings(config)
        setTunnelNetworkSettings(settings) { [weak self] error in
            guard let self else { return }
            if let error {
                os_log("setTunnelNetworkSettings failed: %{public}@", log: self.log, type: .error, "\(error)")
                MobileStop()
                completionHandler(error)
                return
            }

            // 3. Adopt the utun fd and hand it to hev-socks5-tunnel.
            guard let tunFd = self.tunnelFileDescriptor() else {
                let err = NSError(domain: "PacketTunnel", code: 1,
                                  userInfo: [NSLocalizedDescriptionKey: "utun fd not found"])
                os_log("utun fd not found", log: self.log, type: .error)
                MobileStop()
                completionHandler(err)
                return
            }

            self.startHev(tunFd: tunFd, config: config)
            os_log("tunnel up", log: self.log, type: .info)
            completionHandler(nil)
        }
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        os_log("stopping tunnel (reason=%d)", log: log, type: .info, reason.rawValue)
        stateLock.lock()
        stopped = true
        let quitHev = hevRunning
        hevRunning = false
        stateLock.unlock()
        if quitHev {
            hev_socks5_tunnel_quit()
        }
        MobileStop()
        completionHandler()
    }

    // MARK: olcRTC engine (local SOCKS5)

    private func startOlcRtc(_ config: TunnelConfig) -> Error? {
        MobileSetProviders()
        MobileSetTransport(config.transportName)
        MobileSetDNS(config.dns)
        MobileSetVP8Options(Int(config.vp8Fps), Int(config.vp8BatchSize))

        if MobileIsRunning() {
            MobileStop()
        }

        var error: NSError?
        let started = MobileStartWithTransport(
            config.carrierName,
            config.transportName,
            config.roomId,
            config.clientId,
            config.keyHex,
            Int(config.socksPort),
            config.socksUser,
            config.socksPass,
            &error
        )
        guard started else {
            return error ?? NSError(domain: "PacketTunnel", code: 2,
                                    userInfo: [NSLocalizedDescriptionKey: "olcRTC start failed"])
        }

        let ready = MobileWaitReady(8_000, &error)
        guard ready else {
            MobileStop()
            return error ?? NSError(domain: "PacketTunnel", code: 3,
                                    userInfo: [NSLocalizedDescriptionKey: "olcRTC start timed out"])
        }
        return nil
    }

    // MARK: hev-socks5-tunnel

    private func startHev(tunFd: Int32, config: TunnelConfig) {
        let yaml = hevConfigYAML(config)
        // hev_socks5_tunnel_main_from_str blocks until _quit; run it off the provider
        // thread. It dups nothing — it adopts `tunFd` directly (tunnel_init sets it
        // non-blocking and marks it non-local, so it will not close our utun fd).
        // Strong self on purpose: hev outlives any provider callback, and the block
        // must observe stopped/hevRunning through the same instance stopTunnel uses.
        hevQueue.async {
            self.stateLock.lock()
            if self.stopped {
                // stopTunnel won the race before hev's main ever ran; starting it now
                // would leave it running with no one to quit it.
                self.stateLock.unlock()
                return
            }
            self.hevRunning = true
            self.stateLock.unlock()

            var rc: Int32 = 0
            yaml.withCString { cstr in
                let len = UInt32(strlen(cstr))
                cstr.withMemoryRebound(to: UInt8.self, capacity: Int(len)) { bytes in
                    rc = hev_socks5_tunnel_main_from_str(bytes, len, tunFd)
                }
            }

            self.stateLock.lock()
            let wasStopped = self.stopped
            self.hevRunning = false
            self.stateLock.unlock()
            os_log("hev exited rc=%d", log: self.log, type: .info, rc)
            if !wasStopped {
                // hev died on its own (bad config, fd error) — without this the
                // tunnel stays "connected" with a dead data path.
                let err = NSError(domain: "PacketTunnel", code: 4,
                                  userInfo: [NSLocalizedDescriptionKey: "hev-socks5-tunnel exited (rc=\(rc))"])
                self.cancelTunnelWithError(err)
            }
        }
    }

    /// YAML single-quoted scalar: the only escape is doubling embedded quotes.
    private func yamlQuoted(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "''") + "'"
    }

    private func hevConfigYAML(_ config: TunnelConfig) -> String {
        // The utun fd is passed directly, so `tunnel.name`/`ipv4` are ignored on iOS;
        // interface addressing is done by NEPacketTunnelNetworkSettings above.
        return """
        tunnel:
          mtu: \(config.mtu)

        socks5:
          address: 127.0.0.1
          port: \(config.socksPort)
          udp: 'tcp'
          pipeline: false
          username: \(yamlQuoted(config.socksUser))
          password: \(yamlQuoted(config.socksPass))

        mapdns:
          address: \(config.dnsAddress)
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000

        misc:
          task-stack-size: 24576
          tcp-buffer-size: 4096
          max-session-count: 1200
          connect-timeout: 10000
          tcp-read-write-timeout: 300000
          udp-read-write-timeout: 60000
          log-file: stderr
          log-level: warn
        """
    }

    // MARK: utun interface

    private func makeNetworkSettings(_ config: TunnelConfig) -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")

        let ipv4 = NEIPv4Settings(addresses: ["10.0.88.88"], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4

        let dns = NEDNSSettings(servers: [config.dnsAddress])
        // Force all DNS through the tunnel so hev's mapdns can resolve via SOCKS.
        dns.matchDomains = [""]
        settings.dnsSettings = dns

        settings.mtu = NSNumber(value: config.mtu)
        return settings
    }

    /// Finds the utun file descriptor owned by this provider by probing every open fd
    /// for the UTUN_OPT_IFNAME control-socket option (the same identification hev uses
    /// internally). This is the standard NEPacketTunnelProvider technique, because the
    /// API exposes packets via `packetFlow` but not the raw fd hev needs.
    private func tunnelFileDescriptor() -> Int32? {
        // SYSPROTO_CONTROL = 2, UTUN_OPT_IFNAME = 2 (from <sys/sys_domain.h> /
        // <net/if_utun.h>); used as literals to avoid extra C module imports.
        for fd: Int32 in 0...1024 {
            var name = [CChar](repeating: 0, count: Int(IFNAMSIZ))
            var len = socklen_t(name.count)
            let ret = getsockopt(fd, 2, 2, &name, &len)
            if ret == 0 && String(cString: name).hasPrefix("utun") {
                return fd
            }
        }
        return nil
    }
}

/// Parsed provider configuration handed over by the containing app.
private struct TunnelConfig {
    let carrierName: String
    let transportName: String
    let roomId: String
    let clientId: String
    let keyHex: String
    let socksPort: Int
    let socksUser: String
    let socksPass: String
    let vp8Fps: Int
    let vp8BatchSize: Int
    let mtu: Int
    let dns: String          // "1.1.1.1:53" for the olcRTC engine
    let dnsAddress: String    // "1.1.1.1" for NEDNSSettings / hev mapdns

    init(protocolConfiguration: NEVPNProtocol?) throws {
        guard
            let proto = protocolConfiguration as? NETunnelProviderProtocol,
            let dict = proto.providerConfiguration
        else {
            throw NSError(domain: "PacketTunnel", code: 10,
                          userInfo: [NSLocalizedDescriptionKey: "missing providerConfiguration"])
        }
        func str(_ key: String) throws -> String {
            guard let v = dict[key] as? String else {
                throw NSError(domain: "PacketTunnel", code: 11,
                              userInfo: [NSLocalizedDescriptionKey: "missing key: \(key)"])
            }
            return v
        }
        func intVal(_ key: String, _ fallback: Int) -> Int {
            (dict[key] as? NSNumber)?.intValue ?? Int(dict[key] as? String ?? "") ?? fallback
        }

        carrierName = try str("carrierName")
        transportName = try str("transportName")
        roomId = try str("roomId")
        clientId = try str("clientId")
        keyHex = try str("keyHex")
        socksPort = intVal("socksPort", 10808)
        socksUser = (dict["socksUser"] as? String) ?? ""
        socksPass = (dict["socksPass"] as? String) ?? ""
        vp8Fps = intVal("vp8Fps", 30)
        vp8BatchSize = intVal("vp8BatchSize", 8)
        mtu = intVal("mtu", 1500)
        dnsAddress = (dict["dnsAddress"] as? String) ?? "1.1.1.1"
        dns = "\(dnsAddress):53"
    }
}
