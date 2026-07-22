import Foundation
import NetworkExtension
import SharedUI

/// App-side control surface for TUN (VPN) mode, implementing the shared
/// `IosTunnelBridge`. It installs/loads a `NETunnelProviderManager` pointing at the
/// PacketTunnel extension and starts/stops it. All data-path work happens inside the
/// extension; this class only manages the VPN profile and relays status.
final class SwiftTunnelManager: NSObject, IosTunnelBridge {
    private static let extensionBundleId = "org.olcbox.app.ios.PacketTunnel"
    private static let appGroup = "group.org.olcbox.app"

    private var manager: NETunnelProviderManager?
    private var statusListener: IosTunnelStatusListener?
    private var configured = false

    override init() {
        super.init()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(vpnStatusChanged(_:)),
            name: .NEVPNStatusDidChange,
            object: nil
        )
        reload()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: IosTunnelBridge

    func setStatusListener(listener: IosTunnelStatusListener?) {
        statusListener = listener
    }

    func isConfigured() -> Bool {
        configured
    }

    func isActive() -> Bool {
        switch manager?.connection.status {
        case .connected, .connecting, .reasserting:
            return true
        default:
            return false
        }
    }

    func start(request: IosTunnelStartRequest, callback: IosMessageCallback) {
        NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, error in
            guard let self else { return }
            if let error {
                callback.onError(message: "Load VPN preferences failed: \(error.localizedDescription)")
                return
            }
            let manager = managers?.first ?? NETunnelProviderManager()
            manager.localizedDescription = "Olcbox"
            manager.protocolConfiguration = self.makeProtocol(request)
            manager.isEnabled = true

            manager.saveToPreferences { saveError in
                if let saveError {
                    callback.onError(message: "Save VPN profile failed: \(saveError.localizedDescription)")
                    return
                }
                // Apple quirk: reload after save before starting, or startVPNTunnel throws.
                manager.loadFromPreferences { loadError in
                    if let loadError {
                        callback.onError(message: "Reload VPN profile failed: \(loadError.localizedDescription)")
                        return
                    }
                    self.manager = manager
                    self.configured = true
                    do {
                        try manager.connection.startVPNTunnel()
                        callback.onSuccess(message: "started")
                    } catch {
                        callback.onError(message: "Start tunnel failed: \(error.localizedDescription)")
                    }
                }
            }
        }
    }

    func stop() {
        manager?.connection.stopVPNTunnel()
    }

    // MARK: Helpers

    private func makeProtocol(_ request: IosTunnelStartRequest) -> NETunnelProviderProtocol {
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = Self.extensionBundleId
        // Not a real remote host; the extension talks to the olcRTC relay itself.
        proto.serverAddress = "olcRTC"
        proto.providerConfiguration = [
            "carrierName": request.carrierName,
            "transportName": request.transportName,
            "roomId": request.roomId,
            "clientId": request.clientId,
            "keyHex": request.keyHex,
            "socksPort": NSNumber(value: request.socksPort),
            "socksUser": request.socksUser,
            "socksPass": request.socksPass,
            "vp8Fps": NSNumber(value: request.vp8Fps),
            "vp8BatchSize": NSNumber(value: request.vp8BatchSize),
            "mtu": NSNumber(value: request.mtu),
            "dnsAddress": request.dnsAddress
        ]
        return proto
    }

    private func reload() {
        NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, _ in
            guard let self else { return }
            self.manager = managers?.first
            self.configured = (self.manager != nil)
        }
    }

    @objc private func vpnStatusChanged(_ note: Notification) {
        let status: String
        switch manager?.connection.status ?? .invalid {
        case .invalid: status = "invalid"
        case .disconnected: status = "disconnected"
        case .connecting: status = "connecting"
        case .connected: status = "connected"
        case .reasserting: status = "reconnecting"
        case .disconnecting: status = "disconnecting"
        @unknown default: status = "invalid"
        }
        statusListener?.onStatus(status: status, message: nil)
    }
}
