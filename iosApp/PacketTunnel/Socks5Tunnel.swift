import Darwin
import Foundation
import HevSocks5Tunnel

enum Socks5Tunnel {
    private static var tunnelFileDescriptor: Int32? {
        var controlInfo = olcbox_ctl_info()
        withUnsafeMutablePointer(to: &controlInfo.ctl_name) {
            $0.withMemoryRebound(to: CChar.self, capacity: MemoryLayout.size(ofValue: $0.pointee)) {
                _ = strcpy($0, "com.apple.net.utun_control")
            }
        }

        for descriptor in Int32(0)...Int32(1024) {
            var address = olcbox_sockaddr_ctl()
            var length = socklen_t(MemoryLayout.size(ofValue: address))
            let result = withUnsafeMutablePointer(to: &address) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    getpeername(descriptor, $0, &length)
                }
            }
            guard result == 0, address.sc_family == AF_SYSTEM else { continue }
            if controlInfo.ctl_id == 0, ioctl(descriptor, OLCBOX_CTLIOCGINFO, &controlInfo) != 0 { continue }
            if address.sc_id == controlInfo.ctl_id { return descriptor }
        }
        return nil
    }

    static func run(configuration: String, completion: @escaping @Sendable (Int32) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            guard let descriptor = tunnelFileDescriptor else {
                completion(-1)
                return
            }
            let bytes = Array(configuration.utf8)
            let result = bytes.withUnsafeBufferPointer { buffer -> Int32 in
                guard let address = buffer.baseAddress else { return -1 }
                return hev_socks5_tunnel_main_from_str(address, UInt32(buffer.count), descriptor)
            }
            completion(result)
        }
    }

    static func quit() {
        hev_socks5_tunnel_quit()
    }
}
