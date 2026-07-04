package org.olcbox.app.data.routing

import org.olcbox.app.data.model.RoutingPolicyConfig
import org.olcbox.app.data.model.RoutingRuleAction
import org.olcbox.app.data.model.RoutingRuleType
import org.olcbox.app.data.model.RoutingSplitTunnelMode

data class RoutingPolicyRuntimePlan(
    val splitTunnel: RoutingSplitTunnelMode = RoutingSplitTunnelMode.FullTunnel,
    val proxyIpv4Cidrs: List<Ipv4Cidr> = emptyList(),
    val bypassIpv4Cidrs: List<Ipv4Cidr> = emptyList(),
    val domainRuleCount: Int = 0,
    val geoRuleCount: Int = 0,
    val datListCount: Int = 0,
    val datCategoryCount: Int = 0,
    val unsupportedRuleCount: Int = 0
) {
    val hasPolicy: Boolean
        get() = this != Empty

    val hasRouteLevelRules: Boolean
        get() = proxyIpv4Cidrs.isNotEmpty() || bypassIpv4Cidrs.isNotEmpty()

    val hasResolverRules: Boolean
        get() = domainRuleCount > 0 || geoRuleCount > 0 || datListCount > 0

    fun summary(): String {
        if (!hasPolicy) return "Routing policy: none"

        val parts = listOfNotNull(
            "mode=${splitTunnel.serializedName()}",
            proxyIpv4Cidrs.size.takeIf { it > 0 }?.let { "proxy CIDR=$it" },
            bypassIpv4Cidrs.size.takeIf { it > 0 }?.let { "bypass CIDR=$it" },
            domainRuleCount.takeIf { it > 0 }?.let { "domain=$it" },
            geoRuleCount.takeIf { it > 0 }?.let { "geo/dat=$it" },
            datListCount.takeIf { it > 0 }?.let { "dat lists=$it" },
            unsupportedRuleCount.takeIf { it > 0 }?.let { "unsupported=$it" }
        )

        return "Routing policy: ${parts.joinToString(", ")}"
    }

    companion object {
        val Empty = RoutingPolicyRuntimePlan()
    }
}

object RoutingPolicyPlanner {
    fun plan(policy: RoutingPolicyConfig?): RoutingPolicyRuntimePlan {
        val normalized = policy?.normalized()?.takeUnless { it.isEmpty() }
            ?: return RoutingPolicyRuntimePlan.Empty

        val proxyCidrs = mutableListOf<Ipv4Cidr>()
        val bypassCidrs = mutableListOf<Ipv4Cidr>()
        var domainRules = 0
        var geoRules = 0
        var unsupportedRules = 0

        normalized.rules.forEach { rule ->
            when (rule.type) {
                RoutingRuleType.Domain,
                RoutingRuleType.DomainSuffix -> domainRules += 1

                RoutingRuleType.GeoSite,
                RoutingRuleType.GeoIp -> geoRules += 1

                RoutingRuleType.IpCidr -> {
                    val cidr = RoutingPolicyMatcher.parseIpv4Cidr(rule.value)
                    if (cidr == null) {
                        unsupportedRules += 1
                    } else {
                        when (rule.action) {
                            RoutingRuleAction.Proxy -> proxyCidrs += cidr
                            RoutingRuleAction.Bypass -> bypassCidrs += cidr
                        }
                    }
                }
            }
        }

        val datCategories = normalized.datLists.sumOf { it.categories.size }

        return RoutingPolicyRuntimePlan(
            splitTunnel = normalized.splitTunnel,
            proxyIpv4Cidrs = proxyCidrs.distinctBy { it.value },
            bypassIpv4Cidrs = bypassCidrs.distinctBy { it.value },
            domainRuleCount = domainRules,
            geoRuleCount = geoRules,
            datListCount = normalized.datLists.size,
            datCategoryCount = datCategories,
            unsupportedRuleCount = unsupportedRules
        )
    }

    private fun RoutingSplitTunnelMode.serializedName(): String {
        return when (this) {
            RoutingSplitTunnelMode.FullTunnel -> "full_tunnel"
            RoutingSplitTunnelMode.ProxySelected -> "proxy_selected"
            RoutingSplitTunnelMode.BypassSelected -> "bypass_selected"
        }
    }
}
