import Darwin
import AVFoundation
import Foundation
import NetworkExtension
import OlcRtcMobile
import SharedUI
import UIKit

final class SwiftOlcRtcManager: NSObject, @unchecked Sendable, IosOlcRtcBridge {
    private var logWriter: IosLogWriter?
    private var mobileLogWriter: MobileLogWriterAdapter?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var activeMode: ConnectionMode = .stopped
    private let lock = NSLock()
    private let keepAlive = SilentAudioKeepAlive()

    func setLogWriter(writer: IosLogWriter?) {
        lock.lock()
        defer { lock.unlock() }

        logWriter = writer
        if let writer {
            let adapter = MobileLogWriterAdapter(writer: writer)
            mobileLogWriter = adapter
            MobileSetLogWriter(adapter)
        } else {
            mobileLogWriter = nil
            MobileSetLogWriter(nil)
        }
    }

    func start(request: IosOlcRtcStartRequest) -> IosBridgeResult {
        lock.lock()
        defer { lock.unlock() }

        // A Packet Tunnel extension can only be used when both the app and the
        // extension were signed by a profile containing the Network Extension
        // entitlement. Sideloaded/free-profile builds intentionally omit it.
        // Try the system VPN first, then preserve the original local SOCKS mode.
        if hasEmbeddedPacketTunnel {
            do {
                try startSystemTunnel(request: request)
                activeMode = .systemVPN
                logWriter?.writeLog(message: "iOS system VPN connected")
                return IosBridgeResult(success: true, message: nil)
            } catch {
                logWriter?.writeLog(message: "System VPN unavailable; using local SOCKS proxy (\(error.localizedDescription))")
            }
        } else {
            logWriter?.writeLog(message: "Packet Tunnel is not included in this build; using local SOCKS proxy")
        }

        return startLocalProxy(request: request)
    }

    private func startSystemTunnel(request: IosOlcRtcStartRequest) throws {
        do {
            let manager = try loadOrCreateTunnelManager()
            let tunnelProtocol = NETunnelProviderProtocol()
            tunnelProtocol.providerBundleIdentifier = Self.tunnelBundleIdentifier
            tunnelProtocol.serverAddress = "Olcbox"
            tunnelProtocol.providerConfiguration = [
                "carrierName": request.carrierName as NSString,
                "transport": request.transportName as NSString,
                "roomId": request.roomId as NSString,
                "clientId": request.clientId as NSString,
                "keyHex": request.keyHex as NSString,
                "socksPort": NSNumber(value: request.socksPort),
                "socksUser": request.socksUser as NSString,
                "socksPass": request.socksPass as NSString,
                "vp8Fps": NSNumber(value: request.vp8Fps),
                "vp8BatchSize": NSNumber(value: request.vp8BatchSize),
                "mtu": NSNumber(value: 1500)
            ]
            manager.localizedDescription = Self.tunnelDescription
            manager.protocolConfiguration = tunnelProtocol
            manager.isEnabled = true
            try save(manager)
            try load(manager)

            // On the first installation iOS creates the preference entry only
            // after showing the VPN permission sheet. Reload the manager list so
            // startTunnel uses the persisted session rather than the temporary
            // pre-save object, whose connection commonly remains `.invalid`.
            var persistedManager = try loadOrCreateTunnelManager()
            do {
                try startAndWait(persistedManager)
            } catch SystemTunnelError.connectionFailed {
                // The first connection can race the preference daemon even after
                // save/load completed. One fresh reload/retry makes the initial
                // tap behave like the second tap without requiring an app restart.
                Thread.sleep(forTimeInterval: 0.75)
                persistedManager = try loadOrCreateTunnelManager()
                if persistedManager.connection.status != .connected {
                    try startAndWait(persistedManager)
                }
            }
        } catch {
            // Do not leave a half-started VPN around before starting the SOCKS
            // fallback in the containing application.
            if let manager = try? loadOrCreateTunnelManager() {
                manager.connection.stopVPNTunnel()
            }
            throw error
        }
    }

    private func startLocalProxy(request: IosOlcRtcStartRequest) -> IosBridgeResult {
        MobileSetProviders()
        MobileSetTransport(request.transportName)
        MobileSetDNS("1.1.1.1:53")
        MobileSetVP8Options(Int(request.vp8Fps), Int(request.vp8BatchSize))

        if MobileIsRunning() { MobileStop() }

        var error: NSError?
        guard MobileStartWithTransport(
            request.carrierName, request.transportName, request.roomId,
            request.clientId, request.keyHex, Int(request.socksPort),
            request.socksUser, request.socksPass, &error
        ) else {
            activeMode = .stopped
            return IosBridgeResult(success: false, message: error?.localizedDescription ?? "olcRTC start failed")
        }

        guard MobileWaitReady(8_000, &error) else {
            MobileStop()
            activeMode = .stopped
            endBackgroundTaskIfNeeded()
            keepAlive.stop(log: makeLogger())
            return IosBridgeResult(success: false, message: error?.localizedDescription ?? "olcRTC start timed out")
        }

        activeMode = .localProxy
        keepAlive.start(log: makeLogger())
        beginBackgroundTaskIfNeeded()
        return IosBridgeResult(success: true, message: nil)
    }

    func stop() {
        lock.lock()
        defer { lock.unlock() }
        if activeMode == .systemVPN, let manager = try? loadOrCreateTunnelManager() {
            manager.connection.stopVPNTunnel()
        }
        if activeMode == .localProxy || MobileIsRunning() {
            MobileStop()
            endBackgroundTaskIfNeeded()
            keepAlive.stop(log: makeLogger())
        }
        activeMode = .stopped
    }

    func isRunning() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        switch activeMode {
        case .systemVPN:
            guard let manager = try? loadOrCreateTunnelManager() else { return false }
            return manager.connection.status == .connected || manager.connection.status == .connecting || manager.connection.status == .reasserting
        case .localProxy:
            return MobileIsRunning()
        case .stopped:
            return false
        }
    }

    private var hasEmbeddedPacketTunnel: Bool {
        guard let url = Bundle.main.builtInPlugInsURL?
            .appendingPathComponent("PacketTunnel.appex") else { return false }
        return FileManager.default.fileExists(atPath: url.path)
    }

    private func loadOrCreateTunnelManager() throws -> NETunnelProviderManager {
        let box = SynchronousResult<[NETunnelProviderManager]>()
        let semaphore = DispatchSemaphore(value: 0)
        NETunnelProviderManager.loadAllFromPreferences { managers, error in
            box.result = error.map(Result.failure) ?? .success(managers ?? [])
            semaphore.signal()
        }
        guard semaphore.wait(timeout: .now() + 10) == .success else { throw SystemTunnelError.operationTimedOut }
        let managers = try box.result?.get() ?? []
        let manager = managers.first(where: { $0.localizedDescription == Self.tunnelDescription }) ?? NETunnelProviderManager()
        if managers.contains(where: { $0 === manager }) { try load(manager) }
        return manager
    }

    private func save(_ manager: NETunnelProviderManager) throws {
        try waitForPreferenceOperation { completion in manager.saveToPreferences(completionHandler: completion) }
    }

    private func load(_ manager: NETunnelProviderManager) throws {
        try waitForPreferenceOperation { completion in manager.loadFromPreferences(completionHandler: completion) }
    }

    private func waitForPreferenceOperation(_ operation: (@escaping (Error?) -> Void) -> Void) throws {
        let box = SynchronousResult<Void>()
        let semaphore = DispatchSemaphore(value: 0)
        operation { error in
            box.result = error.map(Result.failure) ?? .success(())
            semaphore.signal()
        }
        guard semaphore.wait(timeout: .now() + 10) == .success else { throw SystemTunnelError.operationTimedOut }
        try box.result?.get()
    }

    private func waitUntilConnected(_ session: NETunnelProviderSession) throws {
        let started = Date()
        while Date().timeIntervalSince(started) < 30 {
            let elapsed = Date().timeIntervalSince(started)
            switch session.status {
            case .connected: return
            // A newly saved NETunnelProviderManager briefly reports invalid or
            // disconnected while neagent publishes the configuration. Treat it
            // as a startup state for a few seconds instead of immediately
            // stopping the tunnel and falling back to SOCKS.
            case .invalid where elapsed < 4,
                 .disconnected where elapsed < 4:
                Thread.sleep(forTimeInterval: 0.25)
            case .invalid, .disconnected:
                throw SystemTunnelError.connectionFailed
            default: Thread.sleep(forTimeInterval: 0.25)
            }
        }
        session.stopTunnel()
        throw SystemTunnelError.operationTimedOut
    }

    private func startAndWait(_ manager: NETunnelProviderManager) throws {
        guard let session = manager.connection as? NETunnelProviderSession else {
            throw SystemTunnelError.sessionUnavailable
        }
        if session.status != .connected && session.status != .connecting && session.status != .reasserting {
            try session.startTunnel(options: [:])
        }
        try waitUntilConnected(session)
    }

    private static let tunnelDescription = "Olcbox VPN"
    private static let tunnelBundleIdentifier = "org.olcbox.app.ios.PacketTunnel"

    func ping(request: IosOlcRtcCheckRequest) -> IosLongResult {
        let port = allocateLocalPort()
        guard port > 0 else {
            return IosLongResult(success: false, valueMillis: -1, message: "Could not allocate local SOCKS port")
        }

        var value: Int64 = -1
        var error: NSError?
        let success = MobilePing(
            request.carrierName,
            request.transportName,
            request.roomId,
            request.clientId,
            request.keyHex,
            port,
            Int(request.timeoutMillis),
            request.pingUrl,
            Int(request.vp8Fps),
            Int(request.vp8BatchSize),
            &value,
            &error
        )
        return IosLongResult(
            success: success,
            valueMillis: success ? value : -1,
            message: success ? nil : error?.localizedDescription
        )
    }

    func check(request: IosOlcRtcCheckRequest) -> IosLongResult {
        let port = allocateLocalPort()
        guard port > 0 else {
            return IosLongResult(success: false, valueMillis: -1, message: "Could not allocate local SOCKS port")
        }

        var value: Int64 = -1
        var error: NSError?
        let success = MobileCheck(
            request.carrierName,
            request.transportName,
            request.roomId,
            request.clientId,
            request.keyHex,
            port,
            Int(request.timeoutMillis),
            Int(request.vp8Fps),
            Int(request.vp8BatchSize),
            &value,
            &error
        )
        return IosLongResult(
            success: success,
            valueMillis: success ? value : -1,
            message: success ? nil : error?.localizedDescription
        )
    }

    private func allocateLocalPort() -> Int {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { return -1 }
        defer { close(fd) }

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")

        let bindResult = withUnsafePointer(to: &addr) { pointer -> Int32 in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else { return -1 }

        var boundAddr = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let nameResult = withUnsafeMutablePointer(to: &boundAddr) { pointer -> Int32 in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &length)
            }
        }
        guard nameResult == 0 else { return -1 }

        return Int(UInt16(bigEndian: boundAddr.sin_port))
    }

    private func makeLogger() -> (String) -> Void {
        return { [weak self] message in
            self?.log(message)
        }
    }

    private func log(_ message: String) {
        lock.lock()
        let writer = logWriter
        lock.unlock()
        writer?.writeLog(message: message)
    }

    private func beginBackgroundTaskIfNeeded() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            self.lock.lock()
            let existingTask = self.backgroundTask
            self.lock.unlock()
            guard existingTask == .invalid else { return }

            var newTask: UIBackgroundTaskIdentifier = .invalid
            newTask = UIApplication.shared.beginBackgroundTask(withName: "Olcbox SOCKS") { [weak self] in
                self?.endBackgroundTaskIfNeeded()
            }

            self.lock.lock()
            if self.backgroundTask == .invalid {
                self.backgroundTask = newTask
            } else if newTask != .invalid {
                UIApplication.shared.endBackgroundTask(newTask)
            }
            let writer = self.logWriter
            self.lock.unlock()

            if newTask == .invalid {
                writer?.writeLog(message: "iOS background task unavailable; SOCKS pauses when the app is suspended")
            } else {
                writer?.writeLog(message: "iOS background task active; SOCKS can continue until the system suspends the app")
            }
        }
    }

    private func endBackgroundTaskIfNeeded() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            self.lock.lock()
            let task = self.backgroundTask
            self.backgroundTask = .invalid
            let writer = self.logWriter
            self.lock.unlock()

            guard task != .invalid else { return }
            UIApplication.shared.endBackgroundTask(task)
            writer?.writeLog(message: "iOS background task ended")
        }
    }
}

private final class SynchronousResult<Value>: @unchecked Sendable {
    var result: Result<Value, Error>?
}

private enum ConnectionMode {
    case stopped
    case systemVPN
    case localProxy
}

private enum SystemTunnelError: LocalizedError {
    case sessionUnavailable
    case operationTimedOut
    case connectionFailed

    var errorDescription: String? {
        switch self {
        case .sessionUnavailable: return "System VPN session is unavailable"
        case .operationTimedOut: return "System VPN operation timed out"
        case .connectionFailed: return "System VPN failed to connect"
        }
    }
}

/// Keeps the app alive in the background by continuously rendering an inaudible
/// audio buffer through an active `AVAudioSession`. Combined with the `audio`
/// entry in `UIBackgroundModes` (Info.plist), this prevents iOS from suspending
/// the process, which would otherwise freeze the Go/WebRTC runtime and drop the
/// local SOCKS proxy after the short `beginBackgroundTask` grace period.
///
/// It also re-arms itself after audio interruptions (phone calls, other apps),
/// route changes (headphones, Bluetooth) and media-services resets, so a
/// transient audio event cannot silently kill background execution.
private final class SilentAudioKeepAlive: NSObject, @unchecked Sendable {
    private let queue = DispatchQueue(label: "io.olcbox.keepalive")
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var running = false
    private var log: ((String) -> Void)?

    // Amplitude of the keep-alive tone. Inaudible (~ -70 dBFS) but non-zero so the
    // output route is genuinely "producing audio". If a device still suspends the
    // app in the background, raise this slightly (e.g. 0.001).
    private let amplitude: Float = 0.0003
    private let sampleRate: Double = 44_100
    private let toneHz: Float = 50

    func start(log: @escaping (String) -> Void) {
        queue.async {
            self.log = log
            guard !self.running else { return }
            self.registerObservers()
            if self.activate() {
                self.running = true
            }
        }
    }

    func stop(log: @escaping (String) -> Void) {
        queue.async {
            self.log = log
            guard self.running else { return }
            self.running = false
            self.unregisterObservers()
            self.teardownEngine()
            do {
                try AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
            } catch {
                log("iOS keep-alive session cleanup failed: \(error.localizedDescription)")
            }
            log("iOS keep-alive audio stopped")
        }
    }

    // MARK: - Engine lifecycle (always on `queue`)

    private func activate() -> Bool {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)

            guard let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1),
                  let buffer = makeBuffer(format: format) else {
                log?("iOS keep-alive buffer allocation failed")
                return false
            }

            let engine = AVAudioEngine()
            let player = AVAudioPlayerNode()
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: format)
            try engine.start()
            player.scheduleBuffer(buffer, at: nil, options: [.loops], completionHandler: nil)
            player.play()

            self.engine = engine
            self.player = player
            log?("iOS keep-alive audio active; SOCKS keeps running in background")
            return true
        } catch {
            log?("iOS keep-alive audio failed: \(error.localizedDescription)")
            teardownEngine()
            return false
        }
    }

    private func makeBuffer(format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let frames = AVAudioFrameCount(sampleRate) // 1 second, looped forever
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { return nil }
        buffer.frameLength = frames
        guard let channel = buffer.floatChannelData?[0] else { return buffer }

        if amplitude <= 0 {
            memset(channel, 0, Int(frames) * MemoryLayout<Float>.size)
            return buffer
        }

        let step = 2.0 * Float.pi * toneHz / Float(sampleRate)
        var phase: Float = 0
        for i in 0..<Int(frames) {
            channel[i] = sinf(phase) * amplitude
            phase += step
            if phase > 2 * Float.pi { phase -= 2 * Float.pi }
        }
        return buffer
    }

    private func teardownEngine() {
        player?.stop()
        engine?.stop()
        if let player, let engine {
            engine.detach(player)
        }
        player = nil
        engine = nil
    }

    private func restart(reason: String) {
        queue.async {
            guard self.running else { return }
            self.log?("iOS keep-alive restarting audio (\(reason))")
            self.teardownEngine()
            if !self.activate() {
                self.queue.asyncAfter(deadline: .now() + 1.0) {
                    guard self.running else { return }
                    _ = self.activate()
                }
            }
        }
    }

    // MARK: - Notifications

    private func registerObservers() {
        let center = NotificationCenter.default
        center.addObserver(
            self,
            selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(handleRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(handleMediaReset(_:)),
            name: AVAudioSession.mediaServicesWereResetNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(handleEngineConfigChange(_:)),
            name: .AVAudioEngineConfigurationChange,
            object: nil
        )
    }

    private func unregisterObservers() {
        NotificationCenter.default.removeObserver(self)
    }

    @objc private func handleInterruption(_ note: Notification) {
        guard let info = note.userInfo,
              let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }

        switch type {
        case .began:
            log?("iOS keep-alive: audio interrupted")
        case .ended:
            restart(reason: "interruption ended")
        @unknown default:
            break
        }
    }

    @objc private func handleRouteChange(_ note: Notification) {
        queue.async {
            guard self.running, self.engine?.isRunning != true else { return }
            self.restart(reason: "audio route change")
        }
    }

    @objc private func handleMediaReset(_ note: Notification) {
        restart(reason: "media services reset")
    }

    @objc private func handleEngineConfigChange(_ note: Notification) {
        queue.async {
            guard self.running, self.engine?.isRunning != true else { return }
            self.restart(reason: "engine configuration change")
        }
    }
}

private final class MobileLogWriterAdapter: NSObject, MobileLogWriterProtocol {
    private weak var writer: IosLogWriter?

    init(writer: IosLogWriter) {
        self.writer = writer
    }

    func writeLog(_ msg: String?) {
        writer?.writeLog(message: msg ?? "")
    }
}
