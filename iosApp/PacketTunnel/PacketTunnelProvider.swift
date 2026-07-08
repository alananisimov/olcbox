import Foundation
import NetworkExtension
import OlcRtcMobile

final class PacketTunnelProvider: NEPacketTunnelProvider, @unchecked Sendable {
    private enum NetworkConstants {
        static let tunnelIPv4 = "10.0.88.88"
        static let tunnelIPv6 = "fc00::88"
        static let mappedDNS = "1.1.1.1"
        static let mappedNetwork = "100.64.0.0"
        static let mappedNetmask = "255.192.0.0"
    }

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        do {
            let config = try configuration()
            let completion = TunnelCompletion(completionHandler)
            applyNetworkSettings(mtu: config.int("mtu", fallback: 1500)) { [weak self] error in
                guard let self else { return }
                if let error {
                    completion.call(error)
                    return
                }
                do {
                    try self.startOlcRtc(config)
                    try self.waitForOlcRtc()
                    self.startTun2Socks(config)
                    completion.call(nil)
                } catch {
                    Socks5Tunnel.quit()
                    MobileStop()
                    completion.call(error)
                }
            }
        } catch {
            completionHandler(error)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        Socks5Tunnel.quit()
        MobileStop()
        completionHandler()
    }

    private func configuration() throws -> ProviderConfiguration {
        guard let tunnelProtocol = protocolConfiguration as? NETunnelProviderProtocol,
              let values = tunnelProtocol.providerConfiguration else {
            throw TunnelError.configurationMissing
        }
        return ProviderConfiguration(values: values)
    }

    private func applyNetworkSettings(mtu: Int, completion: @escaping @Sendable (Error?) -> Void) {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: NetworkConstants.tunnelIPv4)
        settings.mtu = NSNumber(value: mtu)

        let ipv4 = NEIPv4Settings(addresses: [NetworkConstants.tunnelIPv4], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = [.default()]
        settings.ipv4Settings = ipv4

        let ipv6 = NEIPv6Settings(
            addresses: [NetworkConstants.tunnelIPv6],
            networkPrefixLengths: [NSNumber(value: 64)]
        )
        ipv6.includedRoutes = [.default()]
        settings.ipv6Settings = ipv6

        // hev-socks5-tunnel's mapdns intercepts this address and maps DNS
        // answers to synthetic 100.64.0.0/10 addresses. Connections to those
        // addresses are then sent to SOCKS by domain name, so DNS never relies
        // on unsupported end-to-end UDP through the local olcRTC SOCKS server.
        let dns = NEDNSSettings(servers: [NetworkConstants.mappedDNS])
        dns.matchDomains = [""]
        dns.matchDomainsNoSearch = true
        settings.dnsSettings = dns
        setTunnelNetworkSettings(settings, completionHandler: completion)
    }

    private func startOlcRtc(_ config: ProviderConfiguration) throws {
        MobileSetProviders()
        MobileSetTransport(config.string("transport"))
        MobileSetDNS("1.1.1.1:53")
        MobileSetSocksListenHost("127.0.0.1")
        MobileSetVP8Options(config.int("vp8Fps", fallback: 60), config.int("vp8BatchSize", fallback: 64))
        var error: NSError?
        let started = MobileStartWithTransport(
            config.string("carrierName"), config.string("transport"), config.string("roomId"),
            config.string("clientId"), config.string("keyHex"), config.int("socksPort", fallback: 10808),
            config.string("socksUser"), config.string("socksPass"), &error
        )
        guard started else { throw error ?? TunnelError.olcRtcStartFailed }
    }

    private func waitForOlcRtc() throws {
        var error: NSError?
        guard MobileWaitReady(20_000, &error) else {
            throw error ?? TunnelError.olcRtcReadyTimedOut
        }
    }

    private func startTun2Socks(_ config: ProviderConfiguration) {
        let port = config.int("socksPort", fallback: 10808)
        let user = config.string("socksUser").yamlQuoted
        let pass = config.string("socksPass").yamlQuoted
        let mtu = config.int("mtu", fallback: 1500)
        let yaml = """
        tunnel:
          name: tun0
          mtu: \(mtu)
          multi-queue: false
          ipv4: \(NetworkConstants.tunnelIPv4)
          ipv6: '\(NetworkConstants.tunnelIPv6)'

        socks5:
          port: \(port)
          address: 127.0.0.1
          udp: 'tcp'
          pipeline: false
          username: '\(user)'
          password: '\(pass)'

        mapdns:
          address: \(NetworkConstants.mappedDNS)
          port: 53
          network: \(NetworkConstants.mappedNetwork)
          netmask: \(NetworkConstants.mappedNetmask)
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
        Socks5Tunnel.run(configuration: yaml) { [weak self] code in
            guard code != 0 else { return }
            self?.cancelTunnelWithError(TunnelError.tun2SocksExited(code))
        }
    }
}

private struct ProviderConfiguration: @unchecked Sendable {
    let values: [String: Any]
    func string(_ key: String) -> String { values[key] as? String ?? "" }
    func int(_ key: String, fallback: Int) -> Int { (values[key] as? NSNumber)?.intValue ?? fallback }
}

private final class TunnelCompletion: @unchecked Sendable {
    private let handler: (Error?) -> Void
    init(_ handler: @escaping (Error?) -> Void) { self.handler = handler }
    func call(_ error: Error?) { handler(error) }
}

private enum TunnelError: LocalizedError {
    case configurationMissing
    case olcRtcStartFailed
    case olcRtcReadyTimedOut
    case tun2SocksExited(Int32)
    var errorDescription: String? {
        switch self {
        case .configurationMissing: return "VPN configuration is missing"
        case .olcRtcStartFailed: return "olcRTC failed to start"
        case .olcRtcReadyTimedOut: return "olcRTC did not become ready"
        case .tun2SocksExited(let code): return "tun2socks exited with code \(code)"
        }
    }
}

private extension String {
    var yamlQuoted: String { replacingOccurrences(of: "'", with: "''") }
}
