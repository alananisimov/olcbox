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
- Android TUN applies bypass `ip_cidr` rules with `VpnService.Builder.excludeRoute` on Android 13+.
- Linux TUN applies bypass `ip_cidr` rules with high-priority `ip rule to <cidr> lookup main` entries.
- Windows TUN and system-proxy modes log routing policy state but do not enforce route exclusions yet.
- `domain`, `domain_suffix`, `geosite`, `geoip`, and `.dat` list references are imported, preserved, summarized, and visible to runtime logs/UI.

Domain and `.dat` rules need a DNS/mapdns resolver layer before they can be enforced reliably. TUN packet routing sees IP packets; it does not automatically know the original domain. The resolver layer must map DNS answers back to policy decisions before domain/geosite/geoip rules can safely affect routing.
