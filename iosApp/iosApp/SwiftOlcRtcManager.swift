import Darwin
import AVFoundation
import CFNetwork
import Foundation
import Network
import OlcRtcMobile
import SharedUI
import UIKit

final class SwiftOlcRtcManager: NSObject, @unchecked Sendable, IosOlcRtcBridge {
    private var logWriter: IosLogWriter?
    private var runtime = MobileNew()!
    private let logLock = NSLock()
    private lazy var nativeLogWriter = NativeLogWriter { [weak self] in
        self?.trackSession($0)
        self?.log($0)
    }
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private let lock = NSLock()
    private let keepAlive = SilentAudioKeepAlive()
    private var startedNetwork: String?
    private var sessionLostAt: TimeInterval?   // guarded by logLock
    private static let sessionRestoreTimeout: TimeInterval = 30

    func setLogWriter(writer: IosLogWriter?) {
        logLock.lock()
        defer { logLock.unlock() }
        logWriter = writer
    }

    func start(request: IosOlcRtcStartRequest) -> IosBridgeResult {
        let interface = PhysicalInterface.current()
        guard let dnsServer = OlcRtcDnsSelector.select(configured: request.dnsServer, interface: interface, log: makeLogger()) else {
            // Usually the network is still coming up; the reconnect loop retries soon.
            return IosBridgeResult(success: false, message: "No DNS server is reachable")
        }
        lock.lock()
        let previous = runtime
        let next = MobileNew()!
        runtime = next
        startedNetwork = interface?.network
        setSessionLost(false)
        try? previous.stop(1)
        DispatchQueue.global(qos: .utility).async { try? previous.stop(5_000) }
        do {
            next.setLogWriter(nativeLogWriter)
            next.setProtector(InterfaceSocketProtector())
            try next.setProvider(request.carrierName)
            try next.setTransport(request.transportName)
            try next.setRoom(request.roomId)
            try next.setKey(request.keyHex)
            next.setDeviceID(request.clientId)
            try next.setDNS(dnsServer)
            try next.setSocksListenHost("127.0.0.1")
            try next.setSocksPort(Int(request.socksPort))
            try next.setSocksCredentials(request.socksUser, password: request.socksPass)
            try next.setVP8Options(Int(request.vp8Fps), batchSize: Int(request.vp8BatchSize))
            try next.start()
        } catch {
            lock.unlock()
            return IosBridgeResult(success: false, message: error.localizedDescription)
        }
        lock.unlock()

        // Wait outside the lock: stop and a newer selection must be able to cancel us.
        let deadline = ProcessInfo.processInfo.systemUptime + 60
        do {
            while true {
                lock.lock()
                let current = runtime === next
                lock.unlock()
                guard current else {
                    return IosBridgeResult(success: false, message: "Connection cancelled")
                }
                do {
                    try next.waitReady(200)
                    break
                } catch {
                    if error.localizedDescription != "olcRTC runtime readiness timed out" ||
                        ProcessInfo.processInfo.systemUptime >= deadline {
                        throw error
                    }
                }
            }
        } catch {
            lock.lock()
            if runtime === next { stopLocked() }
            lock.unlock()
            return IosBridgeResult(success: false, message: error.localizedDescription)
        }
        lock.lock()
        defer { lock.unlock() }
        guard runtime === next else {
            return IosBridgeResult(success: false, message: "Connection cancelled")
        }
        keepAlive.start(log: makeLogger())
        beginBackgroundTaskIfNeeded()
        return IosBridgeResult(success: true, message: nil)
    }

    func stop() {
        lock.lock()
        stopLocked()
        lock.unlock()
    }

    private func stopLocked() {
        let previous = runtime
        runtime = MobileNew()!
        startedNetwork = nil
        setSessionLost(false)
        try? previous.stop(1)
        DispatchQueue.global(qos: .utility).async { try? previous.stop(5_000) }
        endBackgroundTaskIfNeeded()
        keepAlive.stop(log: makeLogger())
    }

    func isRunning() -> Bool {
        lock.lock()
        let running = runtime.state() == "running"
        let started = startedNetwork
        lock.unlock()
        guard running else { return false }
        // olcRTC reconnects inside a running session but keeps the DNS server it
        // was started with. After a network change report "not running", so the
        // watchdog rebuilds the session with the new network's DNS.
        if let started, let now = PhysicalInterface.current()?.network, now != started {
            log("Physical network changed (\(started) -> \(now)); rebuilding olcRTC")
            return false
        }
        // Its own reconnect can also keep failing the handshake for minutes after
        // a provider drop, while a clean restart takes seconds.
        logLock.lock()
        let lostFor = sessionLostAt.map { ProcessInfo.processInfo.systemUptime - $0 }
        logLock.unlock()
        if let lostFor, lostFor >= Self.sessionRestoreTimeout {
            log("olcRTC has not restored its session for \(Int(lostFor))s; rebuilding olcRTC")
            return false
        }
        return true
    }

    /// olcRTC exposes no session state, so follow it from its log.
    private func trackSession(_ line: String) {
        if line.contains("client reconnect reason=") {
            setSessionLost(true)
        } else if line.contains("opened (device=") {
            setSessionLost(false)
        }
    }

    private func setSessionLost(_ lost: Bool) {
        logLock.lock()
        sessionLostAt = lost ? (sessionLostAt ?? ProcessInfo.processInfo.systemUptime) : nil
        logLock.unlock()
    }

    func ping(request: IosOlcRtcCheckRequest) -> IosLongResult {
        let port = allocateLocalPort()
        guard port > 0 else {
            return IosLongResult(success: false, valueMillis: -1, message: "Could not allocate local SOCKS port")
        }

        do {
            var value: Int64 = -1
            try makeProbe(dnsServer: request.dnsServer).ping(
                request.carrierName,
                transportName: request.transportName,
                roomID: request.roomId,
                deviceID: request.clientId,
                keyHex: request.keyHex,
                socksPort: port,
                timeoutMillis: Int(request.timeoutMillis),
                pingURL: request.pingUrl,
                vp8FPS: Int(request.vp8Fps),
                vp8BatchSize: Int(request.vp8BatchSize),
                ret0_: &value
            )
            return IosLongResult(success: true, valueMillis: value, message: nil)
        } catch {
            return IosLongResult(success: false, valueMillis: -1, message: error.localizedDescription)
        }
    }

    func check(request: IosOlcRtcCheckRequest) -> IosLongResult {
        let port = allocateLocalPort()
        guard port > 0 else {
            return IosLongResult(success: false, valueMillis: -1, message: "Could not allocate local SOCKS port")
        }

        do {
            var value: Int64 = -1
            try makeProbe(dnsServer: request.dnsServer).check(
                request.carrierName,
                transportName: request.transportName,
                roomID: request.roomId,
                deviceID: request.clientId,
                keyHex: request.keyHex,
                socksPort: port,
                timeoutMillis: Int(request.timeoutMillis),
                vp8FPS: Int(request.vp8Fps),
                vp8BatchSize: Int(request.vp8BatchSize),
                ret0_: &value
            )
            return IosLongResult(success: true, valueMillis: value, message: nil)
        } catch {
            return IosLongResult(success: false, valueMillis: -1, message: error.localizedDescription)
        }
    }

    private func makeProbe(dnsServer configured: String) throws -> MobileRuntime {
        let probe = MobileNew()!
        let interface = PhysicalInterface.current()
        probe.setProtector(InterfaceSocketProtector())
        let dnsServer = OlcRtcDnsSelector.select(configured: configured, interface: interface, log: { _ in })
        try probe.setDNS(dnsServer ?? OlcRtcDnsSelector.fallbackServers[1])
        return probe
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
        logLock.lock()
        let writer = logWriter
        logLock.unlock()
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
            self.lock.unlock()

            if newTask == .invalid {
                self.log("iOS background task unavailable; SOCKS pauses when the app is suspended")
            } else {
                self.log("iOS background task active; SOCKS can continue until the system suspends the app")
            }
        }
    }

    private func endBackgroundTaskIfNeeded() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }

            self.lock.lock()
            let task = self.backgroundTask
            self.backgroundTask = .invalid
            self.lock.unlock()

            guard task != .invalid else { return }
            UIApplication.shared.endBackgroundTask(task)
            self.log("iOS background task ended")
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

private final class NativeLogWriter: NSObject, MobileLogWriterProtocol {
    private let output: (String) -> Void

    init(output: @escaping (String) -> Void) { self.output = output }

    func writeLog(_ message: String?) {
        if let message, !message.isEmpty { output(message) }
    }
}

/// Picks the DNS server olcRTC uses for provider signaling.
///
/// olcRTC resolves provider hosts with its own UDP resolver, so it never sees the
/// carrier DNS unless we pass it. Whitelisted mobile networks drop UDP 53 to public
/// resolvers, which made the fixed 1.1.1.1 time out on WB guest-register.
///
/// Candidates, first to answer wins: the DNS of the current physical interface
/// (still known while another VPN such as Happ replaces the default resolver),
/// the system resolver when no VPN is up, servers that answered recently, then
/// public fallbacks. Each one is probed over the physical interface, so a resolver
/// from an earlier network (home 192.168.1.1 after moving to cellular) is skipped.
enum OlcRtcDnsSelector {
    static let fallbackServers = ["77.88.8.8:53", "1.1.1.1:53"]
    private static let recentKey = "ios_recent_dns_servers"
    private static let maxRecent = 5

    /// nil when no candidate answered; the caller fails fast and retries later.
    static func select(configured: String, interface: PhysicalInterface?, log: (String) -> Void) -> String? {
        let trimmed = configured.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            log("Using configured DNS server \(trimmed) for olcRTC signaling")
            return trimmed
        }
        guard let interface else {
            log("No Wi-Fi or cellular interface is up; no DNS server for olcRTC")
            return nil
        }

        let scoped = interfaceServers(index: interface.index, log: log)
        if !scoped.isEmpty { log("System DNS servers of \(interface.name): \(scoped.joined(separator: ", "))") }
        let defaults = UserDefaults.standard
        let recent = defaults.stringArray(forKey: recentKey) ?? []
        var seen = Set<String>()
        let candidates = (scoped + (isVpnActive() ? [] : systemServers()) + recent + fallbackServers)
            .filter { seen.insert($0).inserted }

        for server in candidates {
            guard answers(server, via: interface) else {
                log("DNS server \(server) did not answer via \(interface.name)")
                continue
            }
            if !fallbackServers.contains(server) {
                defaults.set(Array(([server] + recent.filter { $0 != server }).prefix(maxRecent)), forKey: recentKey)
            }
            log("Using DNS server \(server) via \(interface.name) for olcRTC signaling")
            return server
        }
        log("No DNS server answered via \(interface.name)")
        return nil
    }

    // MARK: System resolver

    private typealias ResNinit = @convention(c) (UnsafeMutableRawPointer) -> Int32
    private typealias ResGetServers = @convention(c) (UnsafeMutableRawPointer, UnsafeMutableRawPointer, Int32) -> Int32
    private typealias ResNdestroy = @convention(c) (UnsafeMutableRawPointer) -> Void

    // resolv.h is not visible to Swift without a bridging header, so libresolv is
    // loaded at runtime. Buffers are oversized: __res_9_state is 552 bytes and
    // res_9_sockaddr_union is 128 bytes on arm64.
    private static let stateByteCount = 4096
    private static let sockaddrUnionByteCount = 128
    private static let maxServers = 3 // MAXNS

    /// Default resolver addresses as "host:port", IPv4 first. Another VPN replaces
    /// these with its own resolver.
    static func systemServers() -> [String] {
        guard let handle = dlopen("/usr/lib/libresolv.9.dylib", RTLD_NOW) else { return [] }
        defer { dlclose(handle) }
        guard let ninitSymbol = dlsym(handle, "res_9_ninit"),
              let getServersSymbol = dlsym(handle, "res_9_getservers"),
              let ndestroySymbol = dlsym(handle, "res_9_ndestroy") else { return [] }
        let ninit = unsafeBitCast(ninitSymbol, to: ResNinit.self)
        let getServers = unsafeBitCast(getServersSymbol, to: ResGetServers.self)
        let ndestroy = unsafeBitCast(ndestroySymbol, to: ResNdestroy.self)

        let state = UnsafeMutableRawPointer.allocate(byteCount: stateByteCount, alignment: 16)
        defer { state.deallocate() }
        state.initializeMemory(as: UInt8.self, repeating: 0, count: stateByteCount)
        guard ninit(state) == 0 else { return [] }
        defer { ndestroy(state) }

        let listByteCount = sockaddrUnionByteCount * maxServers
        let list = UnsafeMutableRawPointer.allocate(byteCount: listByteCount, alignment: 16)
        defer { list.deallocate() }
        list.initializeMemory(as: UInt8.self, repeating: 0, count: listByteCount)
        let count = min(Int(getServers(state, list, Int32(maxServers))), maxServers)
        return ordered((0..<max(count, 0)).compactMap { server(list + $0 * sockaddrUnionByteCount) })
    }

    // MARK: Per-interface resolvers

    private typealias DnsConfigurationCopy = @convention(c) () -> UnsafeMutableRawPointer?
    private typealias DnsConfigurationFree = @convention(c) (UnsafeMutableRawPointer) -> Void

    /// DNS servers the system keeps for one interface ("scoped" resolvers), which
    /// stay valid while another VPN owns the default resolver. dns_configuration_copy
    /// is libSystem SPI from configd's dnsinfo.h, read with that header's layout
    /// (#pragma pack(4), pointers stored as 8 bytes). Counts and pointers are
    /// range-checked, so an unexpected layout yields no servers instead of a crash
    /// and the other candidates take over.
    private static func interfaceServers(index: UInt32, log: (String) -> Void) -> [String] {
        let defaultHandle = UnsafeMutableRawPointer(bitPattern: -2) // RTLD_DEFAULT
        guard let copySymbol = dlsym(defaultHandle, "dns_configuration_copy"),
              let freeSymbol = dlsym(defaultHandle, "dns_configuration_free"),
              let config = unsafeBitCast(copySymbol, to: DnsConfigurationCopy.self)() else {
            log("System DNS configuration is unavailable")
            return []
        }
        defer { unsafeBitCast(freeSymbol, to: DnsConfigurationFree.self)(config) }

        // dns_config_t: n_scoped_resolver at 12, scoped_resolver at 16.
        guard let scopedCount = readCount(config, 12), let resolvers = pointer(config, 16, near: config) else {
            log("System DNS configuration has no per-interface resolvers")
            return []
        }
        for slot in 0..<scopedCount {
            // dns_resolver_t: n_nameserver at 8, nameserver at 12, if_index at 64.
            guard let resolver = pointer(resolvers, slot * 8, near: config),
                  resolver.loadUnaligned(fromByteOffset: 64, as: UInt32.self) == index,
                  let total = readCount(resolver, 8),
                  let nameservers = pointer(resolver, 12, near: config) else { continue }
            let found = (0..<total).compactMap { pointer(nameservers, $0 * 8, near: config).flatMap(server) }
            if !found.isEmpty { return ordered(found) }
        }
        log("System DNS configuration has \(scopedCount) per-interface resolvers, none for interface \(index)")
        return []
    }

    /// A pointer stored at base+offset, accepted only inside the configuration
    /// buffer (dnsinfo keeps every entry in one allocation).
    private static func pointer(_ base: UnsafeRawPointer, _ offset: Int, near buffer: UnsafeRawPointer) -> UnsafeRawPointer? {
        let value = UInt(base.loadUnaligned(fromByteOffset: offset, as: UInt64.self))
        let start = UInt(bitPattern: buffer)
        guard value > start, value - start < 1 << 20 else { return nil }
        return UnsafeRawPointer(bitPattern: value)
    }

    private static func readCount(_ base: UnsafeRawPointer, _ offset: Int) -> Int? {
        let value = Int(base.loadUnaligned(fromByteOffset: offset, as: Int32.self))
        return (1...32).contains(value) ? value : nil
    }

    // MARK: Addresses

    /// "host:port" for a resolver sockaddr; nil for loopback, unspecified,
    /// link-local and the 198.18.0.0/15 fake-IP range.
    private static func server(_ entry: UnsafeRawPointer) -> String? {
        switch Int32(entry.loadUnaligned(as: sockaddr.self).sa_family) {
        case AF_INET:
            let address = entry.loadUnaligned(as: sockaddr_in.self)
            let host = UInt32(bigEndian: address.sin_addr.s_addr)
            if host >> 24 == 127 || host == 0 || host & 0xFFFE_0000 == 0xC612_0000 { return nil }
            var raw = address.sin_addr
            return presentation(AF_INET, &raw).map { "\($0):\(port(address.sin_port))" }
        case AF_INET6:
            let address = entry.loadUnaligned(as: sockaddr_in6.self)
            var raw = address.sin6_addr
            let bytes = withUnsafeBytes(of: &raw) { Array($0) }
            let isUnspecified = bytes.allSatisfy { $0 == 0 }
            let isLoopback = bytes.dropLast().allSatisfy { $0 == 0 } && bytes.last == 1
            let isLinkLocal = bytes[0] == 0xFE && bytes[1] & 0xC0 == 0x80
            if isUnspecified || isLoopback || isLinkLocal { return nil }
            return presentation(AF_INET6, &raw).map { "[\($0)]:\(port(address.sin6_port))" }
        default:
            return nil
        }
    }

    private static func ordered(_ servers: [String]) -> [String] {
        servers.filter { !$0.hasPrefix("[") } + servers.filter { $0.hasPrefix("[") }
    }

    private static func presentation(_ family: Int32, _ address: UnsafeRawPointer) -> String? {
        var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        guard inet_ntop(family, address, &buffer, socklen_t(buffer.count)) != nil else { return nil }
        return buffer.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }
    }

    private static func port(_ networkOrder: in_port_t) -> UInt16 {
        let value = UInt16(bigEndian: networkOrder)
        return value == 0 ? 53 : value
    }

    // MARK: Probing

    /// Whether `server` answers one query for stream.wb.ru over `interface` within
    /// a second. Any reply carrying our ID counts, even NXDOMAIN.
    private static func answers(_ server: String, via interface: PhysicalInterface) -> Bool {
        guard let target = socketAddress(server) else { return false }
        var address = target.address
        let fd = socket(Int32(address.ss_family), SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return false }
        defer { close(fd) }
        interface.bind(fd)

        let id = UInt16.random(in: .min ... .max)
        var query: [UInt8] = [UInt8(id >> 8), UInt8(id & 0xFF), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]
        for label in ["stream", "wb", "ru"] {
            query.append(UInt8(label.utf8.count))
            query.append(contentsOf: label.utf8)
        }
        query.append(contentsOf: [0, 0, 1, 0, 1]) // root, type A, class IN
        let sent = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sendto(fd, query, query.count, 0, $0, target.length) }
        }
        var poller = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
        guard sent == query.count, poll(&poller, 1, 1_000) == 1 else { return false }
        var reply = [UInt8](repeating: 0, count: 512)
        let received = recv(fd, &reply, 512, 0)
        return received >= 12 && reply[0] == query[0] && reply[1] == query[1] && reply[2] & 0x80 != 0
    }

    /// Parses "host:port" or "[v6]:port" with a numeric host.
    private static func socketAddress(_ server: String) -> (address: sockaddr_storage, length: socklen_t)? {
        var host = Substring(server)
        var port = Substring("53")
        if host.hasPrefix("["), let end = host.firstIndex(of: "]") {
            let rest = host[host.index(after: end)...]
            if rest.hasPrefix(":") { port = rest.dropFirst() }
            host = host[host.index(after: host.startIndex)..<end]
        } else if let colon = host.lastIndex(of: ":") {
            port = host[host.index(after: colon)...]
            host = host[..<colon]
        }
        var hints = addrinfo()
        hints.ai_flags = AI_NUMERICHOST | AI_NUMERICSERV
        hints.ai_socktype = SOCK_DGRAM
        var info: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(String(host), String(port), &hints, &info) == 0, let first = info else { return nil }
        defer { freeaddrinfo(first) }
        guard let source = first.pointee.ai_addr else { return nil }
        var storage = sockaddr_storage()
        let length = first.pointee.ai_addrlen
        withUnsafeMutableBytes(of: &storage) {
            $0.copyMemory(from: UnsafeRawBufferPointer(start: source, count: Int(length)))
        }
        return (storage, length)
    }

    private static func isVpnActive() -> Bool {
        guard let settings = CFNetworkCopySystemProxySettings()?.takeRetainedValue() as? [String: Any],
              let scoped = settings["__SCOPED__"] as? [String: Any] else { return false }
        let tunnelPrefixes = ["utun", "ipsec", "ppp", "tap", "tun"]
        return scoped.keys.contains { name in tunnelPrefixes.contains { name.hasPrefix($0) } }
    }
}

/// The interface the phone would use without a VPN: what the system path monitor
/// prefers (Wi-Fi, or the cellular line chosen for data), falling back to Wi-Fi
/// (en0) with an IPv4 address, then the first cellular interface.
struct PhysicalInterface {
    let name: String
    let index: UInt32
    let address: String

    /// Changes with the interface or its address, i.e. on any network switch.
    var network: String { "\(name) \(address)" }

    // netinet/in.h and netinet6/in6.h
    private static let ipBoundIf: Int32 = 25
    private static let ipv6BoundIf: Int32 = 125

    static func current() -> PhysicalInterface? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0 else { return nil }
        defer { freeifaddrs(head) }
        var order: [String] = []
        var ipv4: [String: String] = [:]
        var ipv6: [String: String] = [:]
        var cursor = head
        while let entry = cursor {
            cursor = entry.pointee.ifa_next
            guard let address = entry.pointee.ifa_addr, entry.pointee.ifa_flags & UInt32(IFF_UP) != 0 else { continue }
            let name = String(cString: entry.pointee.ifa_name)
            let family = Int32(address.pointee.sa_family)
            if family == AF_INET, ipv4[name] == nil {
                ipv4[name] = text(address)
            } else if family == AF_INET6, ipv6[name] == nil, !isLinkLocal(address) {
                ipv6[name] = text(address)
            } else {
                continue
            }
            if !order.contains(name) { order.append(name) }
        }
        // With two SIMs both cellular interfaces keep addresses; only the path
        // monitor knows which line carries data.
        let name = PhysicalPath.shared.interfaceName()
            ?? (ipv4["en0"] != nil ? "en0" : order.first { $0.hasPrefix("pdp_ip") })
        guard let name, let address = ipv4[name] ?? ipv6[name] else { return nil }
        let index = if_nametoindex(name)
        return index == 0 ? nil : PhysicalInterface(name: name, index: index, address: address)
    }

    private static func text(_ address: UnsafeMutablePointer<sockaddr>) -> String {
        let raw = UnsafeRawPointer(address)
        if Int32(address.pointee.sa_family) == AF_INET {
            var ip = raw.loadUnaligned(as: sockaddr_in.self).sin_addr
            return withUnsafeBytes(of: &ip) { $0.map { String($0) }.joined(separator: ".") }
        }
        var ip = raw.loadUnaligned(as: sockaddr_in6.self).sin6_addr
        return withUnsafeBytes(of: &ip) { $0.map { String(format: "%02x", $0) }.joined() }
    }

    private static func isLinkLocal(_ address: UnsafeMutablePointer<sockaddr>) -> Bool {
        var raw = UnsafeRawPointer(address).loadUnaligned(as: sockaddr_in6.self).sin6_addr
        let bytes = withUnsafeBytes(of: &raw) { Array($0) }
        return bytes[0] == 0xFE && bytes[1] & 0xC0 == 0x80
    }

    /// Scopes a socket to this interface (IP_BOUND_IF / IPV6_BOUND_IF, as
    /// Network.framework's requiredInterface does), so its traffic ignores the
    /// routes of another VPN.
    func bind(_ fd: Int32) {
        var value = index
        let size = socklen_t(MemoryLayout<UInt32>.size)
        _ = setsockopt(fd, IPPROTO_IP, Self.ipBoundIf, &value, size)
        _ = setsockopt(fd, IPPROTO_IPV6, Self.ipv6BoundIf, &value, size)
    }
}

/// Tracks the interface iOS would use if no VPN were up. VPN tunnels are "other"
/// interfaces and are left out, so the answer stays right while Happ is up and
/// follows a switch of the cellular data line.
private final class PhysicalPath: @unchecked Sendable {
    static let shared = PhysicalPath()
    private let monitor = NWPathMonitor(prohibitedInterfaceTypes: [.other])
    private let firstUpdate = DispatchGroup()
    private let lock = NSLock()
    private var name: String?
    private var updated = false

    private init() {
        firstUpdate.enter()
        monitor.pathUpdateHandler = { [weak self] path in self?.update(path) }
        monitor.start(queue: DispatchQueue(label: "olcbox.physical-path"))
    }

    private func update(_ path: NWPath) {
        let physical: [NWInterface.InterfaceType] = [.wifi, .cellular, .wiredEthernet]
        let preferred = path.availableInterfaces.first { physical.contains($0.type) }
        lock.lock()
        let first = !updated
        updated = true
        name = path.status == .satisfied ? preferred?.name : nil
        lock.unlock()
        if first { firstUpdate.leave() }
    }

    /// The preferred physical interface; nil while offline or if the monitor
    /// has not reported within a second.
    func interfaceName() -> String? {
        _ = firstUpdate.wait(timeout: .now() + 1)
        lock.lock()
        defer { lock.unlock() }
        return name
    }
}

/// Keeps olcRTC's sockets on the physical network. Sockets opened while another
/// VPN such as Happ is up otherwise enter that VPN, which relays them back into
/// this app's SOCKS proxy, so reconnecting after a network change could never
/// succeed. Android does the same through VpnService.protect.
private final class InterfaceSocketProtector: NSObject, MobileSocketProtectorProtocol {
    func protect(_ fd: Int) -> Bool {
        // Resolved per socket: olcRTC reconnects inside a running session, and
        // after a network change its new sockets must follow the new interface.
        PhysicalInterface.current()?.bind(Int32(truncatingIfNeeded: fd))
        return true
    }
}
