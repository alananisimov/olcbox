package org.olcbox.app.data.routing

import org.olcbox.app.data.model.RoutingPolicyConfig
import org.olcbox.app.data.model.RoutingRuleAction
import org.olcbox.app.data.model.RoutingRuleConfig
import org.olcbox.app.data.model.RoutingRuleType
import org.olcbox.app.data.model.RoutingSplitTunnelMode

data class Ipv4Cidr(
    val value: String,
    val address: String,
    val prefixLength: Int
)

data class DomainRoutingRule(
    val type: RoutingRuleType,
    val value: String,
    val action: RoutingRuleAction
)

enum class RoutingDecision {
    Proxy,
    Bypass
}

object RoutingPolicyMatcher {
    private val privateIpv4Cidrs = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.0.0.0/24",
        "192.0.2.0/24",
        "192.168.0.0/16",
        "198.18.0.0/15",
        "198.51.100.0/24",
        "203.0.113.0/24",
        "224.0.0.0/4",
        "240.0.0.0/4"
    ).mapNotNull { parseIpv4Cidr(it) }

    fun decide(
        policy: RoutingPolicyConfig?,
        host: String? = null,
        ip: String? = null
    ): RoutingDecision {
        val normalizedPolicy = policy?.normalized() ?: return RoutingDecision.Proxy
        val normalizedHost = host.normalizedHost()
        val ipValue = ip?.trim()?.takeIf { it.isNotEmpty() }
        val matchedAction = normalizedPolicy.rules
            .firstOrNull { rule -> rule.matches(normalizedHost, ipValue) }
            ?.action

        return when (matchedAction) {
            RoutingRuleAction.Proxy -> RoutingDecision.Proxy
            RoutingRuleAction.Bypass -> RoutingDecision.Bypass
            null -> normalizedPolicy.defaultDecision()
        }
    }

    private fun RoutingPolicyConfig.defaultDecision(): RoutingDecision {
        return when (splitTunnel) {
            RoutingSplitTunnelMode.FullTunnel,
            RoutingSplitTunnelMode.BypassSelected -> RoutingDecision.Proxy
            RoutingSplitTunnelMode.ProxySelected -> RoutingDecision.Bypass
        }
    }

    private fun RoutingRuleConfig.matches(host: String?, ip: String?): Boolean {
        val ruleValue = value.trim()
        if (ruleValue.isBlank()) return false

        return when (type) {
            RoutingRuleType.Domain -> host != null && host == ruleValue.normalizedHost()
            RoutingRuleType.DomainSuffix -> host != null && host.matchesDomainSuffix(ruleValue)
            RoutingRuleType.IpCidr -> ip != null && ipv4InCidr(ip, ruleValue)
            RoutingRuleType.GeoSite -> false
            RoutingRuleType.GeoIp -> ip != null && geoIpCidrs(ruleValue).any { ipv4InCidr(ip, it.value) }
        }
    }

    fun domainRules(policy: RoutingPolicyConfig?): List<DomainRoutingRule> {
        val normalized = policy?.normalized() ?: return emptyList()
        return normalized.rules.mapNotNull { rule ->
            when (rule.type) {
                RoutingRuleType.Domain,
                RoutingRuleType.DomainSuffix,
                RoutingRuleType.GeoSite -> DomainRoutingRule(
                    type = rule.type,
                    value = rule.value.trim(),
                    action = rule.action
                )

                RoutingRuleType.IpCidr,
                RoutingRuleType.GeoIp -> null
            }
        }
    }

    fun geoIpCidrs(value: String): List<Ipv4Cidr> {
        return when (value.normalizedRuleToken()) {
            "private",
            "geoip:private",
            "lan",
            "geoip:lan",
            "local",
            "geoip:local" -> privateIpv4Cidrs

            else -> emptyList()
        }
    }

    private fun String?.normalizedHost(): String? {
        return this
            ?.trim()
            ?.removeSuffix(".")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun String.matchesDomainSuffix(ruleValue: String): Boolean {
        val suffix = ruleValue.normalizedHost() ?: return false
        return this == suffix || endsWith(".$suffix")
    }

    private fun ipv4InCidr(ip: String, cidr: String): Boolean {
        val parsedCidr = parseIpv4Cidr(cidr) ?: return false
        val network = parseIpv4(parsedCidr.address) ?: return false
        val address = parseIpv4(ip) ?: return false
        val mask = if (parsedCidr.prefixLength == 0) 0 else (-1 shl (32 - parsedCidr.prefixLength))
        return (address and mask) == (network and mask)
    }

    fun parseIpv4Cidr(value: String): Ipv4Cidr? {
        val cidr = value.trim()
        val separator = cidr.indexOf('/')
        if (separator <= 0 || separator == cidr.lastIndex) return null

        val address = cidr.substring(0, separator).trim()
        val prefix = cidr.substring(separator + 1).trim().toIntOrNull() ?: return null
        if (prefix !in 0..32 || parseIpv4(address) == null) return null

        return Ipv4Cidr(
            value = "$address/$prefix",
            address = address,
            prefixLength = prefix
        )
    }

    fun parseIpv4(value: String): Int? {
        val parts = value.trim().split('.')
        if (parts.size != 4) return null

        var result = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            result = (result shl 8) or octet
        }
        return result
    }

    private fun String.normalizedRuleToken(): String {
        return trim().lowercase()
    }
}
