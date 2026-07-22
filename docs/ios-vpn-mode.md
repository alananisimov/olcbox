# iOS VPN (TUN) mode

Adds a full-device tunnel mode on iOS, alongside the existing local-SOCKS5 proxy
mode. Mirrors Android's TUN path but through Apple's `NEPacketTunnelProvider`.

## Data path

```
device packets → utun fd → hev-socks5-tunnel (lwIP tun2socks)
              → 127.0.0.1:socksPort (olcRTC local SOCKS5, hosted in the extension)
              → olcRTC WebRTC relay → internet
```

Everything runs **inside the Network Extension process** so it survives app
suspension — unlike proxy mode, which keeps the app alive with a silent-audio hack
(`SilentAudioKeepAlive`). This is the same reason WireGuard/Outline host their engine
in-extension on iOS.

## Components

| Piece | File |
| --- | --- |
| Packet-tunnel provider | `iosApp/PacketTunnel/PacketTunnelProvider.swift` |
| Extension Info.plist / entitlements | `iosApp/PacketTunnel/{Info.plist,PacketTunnel.entitlements}` |
| App-side VPN control | `iosApp/iosApp/SwiftTunnelManager.swift` (impl of `IosTunnelBridge`) |
| App entitlements | `iosApp/iosApp/iosApp.entitlements` |
| Shared bridge/types | `sharedUI/.../ios/IosBridge.kt` (`IosTunnelBridge`, `IosTunnelStartRequest`) |
| Mode enum | `sharedUI/.../vpn/IosConnectionMode.kt` |
| Mode logic | `sharedUI/.../vpn/IosVpnManager.kt` (TUN branch, status mapping) |
| Mode UI toggle | `sharedUI/.../ui/components/ApplicationSettingsSheet.kt` + `MainViewController.kt` |
| tun2socks engine (iOS) | `HevSocks5Tunnel.xcframework` built from `androidApp/src/main/jni/hev-socks5-tunnel/build-apple-ios.sh` |

The olcRTC engine reuses the existing gomobile symbols (`MobileStartWithTransport`,
`MobileWaitReady`, `MobileStop`), so no change to the `olcrtc` Go repo is required.
`hev_socks5_tunnel_main_from_str(config, len, tunFd)` adopts the utun fd directly
(`tunnel_init` sets it non-blocking when `>= 0`).

> One upstream fix was needed for the stricter Xcode 26 clang:
> `hev-socks5-tunnel/src/hev-socks5-tunnel.c` initialises `res` before
> `write(fd, &res, 1)` (was `-Werror,-Wuninitialized-const-pointer`).

## Build

The extension's pre-build phase "Build Native Frameworks" prepares both engines:

1. `HevSocks5Tunnel.xcframework` — built by `build-apple-ios.sh` (outputs straight
   to `iosApp/Frameworks/`) only when the xcframework is not already there; delete
   it (or run the script manually) to force a rebuild after changing hev sources.
2. `OlcRtcMobile.xcframework` via `./gradlew :sharedUI:buildOlcrtcIosXcframework`
   (needs the `olcrtc` Go repo at `../olcrtc` or `$OLCRTC_REPO`, Go ≥ 1.26.3, and
   `gomobile`/`gobind` on `$HOME/go/bin`).

The `PacketTunnel` target itself is generated: after touching
`iosApp/add_packet_tunnel_target.rb`, re-run `ruby add_packet_tunnel_target.rb`
from `iosApp/` (idempotent) and commit the resulting `project.pbxproj`.

Then `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp`. The app and the
`PacketTunnel.appex` are code-signed externally (both targets have
`CODE_SIGNING_ALLOWED = NO`, matching the existing CI/unsigned-IPA flow).

## Apple provisioning (required to run on device)

A packet-tunnel extension cannot use a wildcard profile. On the team's developer
portal, create:

- **App ID** `org.olcbox.app.ios` — enable *Network Extensions* + *App Groups*.
- **App ID** `org.olcbox.app.ios.PacketTunnel` — same two capabilities.
- **App Group** `group.org.olcbox.app` — assign to both App IDs.
- Development provisioning profiles for both App IDs including the test device.

The entitlements files already declare `packet-tunnel-provider` and the
`group.org.olcbox.app` group. Change the group/bundle IDs consistently if a
different team prefix is used (`SwiftTunnelManager.extensionBundleId`,
`PacketTunnelProvider` OSLog subsystem, both `.entitlements`, both Info.plists).

## Known risks / follow-ups

- **Extension memory budget.** Go + Pion WebRTC + lwIP in one NE extension is heavy
  (NE budget ≈ 50 MB). Needs on-device validation; if it OOMs, trim the Go build or
  move heavy allocation out of the hot path.
- **Routing loop.** The design relies on iOS excluding the provider's own sockets
  from its tunnel (as Outline does). If relay traffic loops, install a protector via
  `MobileSetProtector` that binds engine sockets to the physical interface
  (`IP_BOUND_IF`) — the Go side already routes every dial through
  `internal/protect.Protector`.
- **Mode switch while connected** stops and restarts the tunnel/proxy.
