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

enum class RoutingDecision {
    Proxy,
    Bypass
}

object RoutingPolicyMatcher {
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
            RoutingRuleType.GeoSite,
            RoutingRuleType.GeoIp -> false
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
}
