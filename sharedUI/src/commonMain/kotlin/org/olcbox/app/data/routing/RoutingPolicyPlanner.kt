package org.olcbox.app.data.routing

import org.olcbox.app.data.model.RoutingPolicyConfig
import org.olcbox.app.data.model.RoutingRuleAction
import org.olcbox.app.data.model.RoutingRuleType
import org.olcbox.app.data.model.RoutingSplitTunnelMode

data class RoutingPolicyRuntimePlan(
    val splitTunnel: RoutingSplitTunnelMode = RoutingSplitTunnelMode.FullTunnel,
    val proxyIpv4Cidrs: List<Ipv4Cidr> = emptyList(),
    val bypassIpv4Cidrs: List<Ipv4Cidr> = emptyList(),
    val proxyDomainRules: List<DomainRoutingRule> = emptyList(),
    val bypassDomainRules: List<DomainRoutingRule> = emptyList(),
    val proxyDatCategories: List<String> = emptyList(),
    val bypassDatCategories: List<String> = emptyList(),
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

    val hasMapDnsRules: Boolean
        get() = proxyDomainRules.isNotEmpty() ||
            bypassDomainRules.isNotEmpty() ||
            proxyDatCategories.isNotEmpty() ||
            bypassDatCategories.isNotEmpty()

    fun summary(): String {
        if (!hasPolicy) return "Routing policy: none"

        val parts = listOfNotNull(
            "mode=${splitTunnel.serializedName()}",
            proxyIpv4Cidrs.size.takeIf { it > 0 }?.let { "proxy CIDR=$it" },
            bypassIpv4Cidrs.size.takeIf { it > 0 }?.let { "bypass CIDR=$it" },
            domainRuleCount.takeIf { it > 0 }?.let { "domain=$it" },
            geoRuleCount.takeIf { it > 0 }?.let { "geo=$it" },
            datListCount.takeIf { it > 0 }?.let { "dat lists=$it" },
            datCategoryCount.takeIf { it > 0 }?.let { "dat categories=$it" },
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
        val proxyDomainRules = mutableListOf<DomainRoutingRule>()
        val bypassDomainRules = mutableListOf<DomainRoutingRule>()
        var domainRules = 0
        var geoRules = 0
        var unsupportedRules = 0

        normalized.rules.forEach { rule ->
            when (rule.type) {
                RoutingRuleType.Domain,
                RoutingRuleType.DomainSuffix -> {
                    domainRules += 1
                    when (rule.action) {
                        RoutingRuleAction.Proxy -> proxyDomainRules += DomainRoutingRule(
                            type = rule.type,
                            value = rule.value,
                            action = rule.action
                        )

                        RoutingRuleAction.Bypass -> bypassDomainRules += DomainRoutingRule(
                            type = rule.type,
                            value = rule.value,
                            action = rule.action
                        )
                    }
                }

                RoutingRuleType.GeoSite -> {
                    geoRules += 1
                    when (rule.action) {
                        RoutingRuleAction.Proxy -> proxyDomainRules += DomainRoutingRule(
                            type = rule.type,
                            value = rule.value,
                            action = rule.action
                        )

                        RoutingRuleAction.Bypass -> bypassDomainRules += DomainRoutingRule(
                            type = rule.type,
                            value = rule.value,
                            action = rule.action
                        )
                    }
                }

                RoutingRuleType.GeoIp -> {
                    geoRules += 1
                    val geoCidrs = RoutingPolicyMatcher.geoIpCidrs(rule.value)
                    if (geoCidrs.isEmpty()) {
                        unsupportedRules += 1
                    } else {
                        when (rule.action) {
                            RoutingRuleAction.Proxy -> proxyCidrs += geoCidrs
                            RoutingRuleAction.Bypass -> bypassCidrs += geoCidrs
                        }
                    }
                }

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

        val proxyDatCategories = normalized.datLists
            .filter { it.action == RoutingRuleAction.Proxy }
            .flatMap { it.categories }
            .distinct()
        val bypassDatCategories = normalized.datLists
            .filter { it.action == RoutingRuleAction.Bypass }
            .flatMap { it.categories }
            .distinct()
        val datCategories = proxyDatCategories.size + bypassDatCategories.size

        return RoutingPolicyRuntimePlan(
            splitTunnel = normalized.splitTunnel,
            proxyIpv4Cidrs = proxyCidrs.distinctBy { it.value },
            bypassIpv4Cidrs = bypassCidrs.distinctBy { it.value },
            proxyDomainRules = proxyDomainRules.distinctBy { "${it.type}|${it.value}|${it.action}" },
            bypassDomainRules = bypassDomainRules.distinctBy { "${it.type}|${it.value}|${it.action}" },
            proxyDatCategories = proxyDatCategories,
            bypassDatCategories = bypassDatCategories,
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
