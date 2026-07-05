# Subscription Routing Policy

Olcbox accepts an optional `routing` object in imported subscription JSON.

Supported schema:

```json
{
  "routing": {
    "split_tunnel": "full_tunnel",
    "dat_lists": [
      {
        "name": "geosite",
        "url": "https://example.com/geosite.dat",
        "categories": ["geosite:ru", "geosite:youtube"],
        "action": "bypass"
      }
    ],
    "rules": [
      { "type": "domain_suffix", "value": "youtube.com", "action": "proxy" },
      { "type": "ip_cidr", "value": "10.0.0.0/8", "action": "bypass" }
    ]
  }
}
```

Current runtime support:

- `ip_cidr` rules are parsed, normalized, tested, and included in runtime plans.
- `geoip:private`, `geoip:lan`, and `geoip:local` are expanded into private/reserved IPv4 CIDR rules.
- `domain`, `domain_suffix`, `geosite`, and `.dat` categories are separated into a resolver-level plan with their original `proxy` or `bypass` action preserved.
- Android TUN applies bypass `ip_cidr` rules with `VpnService.Builder.excludeRoute` on Android 13+.
- Linux TUN applies bypass `ip_cidr` rules with high-priority `ip rule to <cidr> lookup main` entries.
- Windows TUN and system-proxy modes log routing policy state but do not enforce route exclusions yet.
- Android TUN and Linux TUN enable `mapdns`, so DNS answers can be mapped back to hostnames before the SOCKS connection is made.
- Hostname-based `proxy` rules can use the mapdns/SOCKS hostname path when traffic is already inside the TUN.
- Hostname-based `bypass` rules still need native direct-route support. Android's `VpnService.Builder` can exclude static IP prefixes, but it cannot dynamically exclude a domain after DNS resolution.

Limitations:

- Country-wide `geoip` values such as `geoip:ru` need a real GeoIP database or generated CIDR bundle before they can become route-level rules.
- `geosite` and external `.dat` list references are preserved in the runtime plan, but this PR does not download or parse third-party `.dat` files yet.
- Apps that use encrypted DNS outside the VPN DNS path, such as DoH/DoT inside the app, may not hit mapdns and therefore may not expose the original domain to the routing layer.
