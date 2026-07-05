package org.olcbox.app.data.routing

import org.olcbox.app.data.model.RoutingPolicyConfig
import org.olcbox.app.data.model.RoutingRuleAction
import org.olcbox.app.data.model.RoutingRuleConfig
import org.olcbox.app.data.model.RoutingRuleType
import org.olcbox.app.data.model.RoutingSplitTunnelMode
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingPolicyMatcherTest {
    @Test
    fun defaultsToProxyForFullTunnelAndBypassSelectedModes() {
        assertEquals(
            RoutingDecision.Proxy,
            RoutingPolicyMatcher.decide(RoutingPolicyConfig(splitTunnel = RoutingSplitTunnelMode.FullTunnel))
        )
        assertEquals(
            RoutingDecision.Proxy,
            RoutingPolicyMatcher.decide(RoutingPolicyConfig(splitTunnel = RoutingSplitTunnelMode.BypassSelected))
        )
    }

    @Test
    fun defaultsToBypassForProxySelectedMode() {
        assertEquals(
            RoutingDecision.Bypass,
            RoutingPolicyMatcher.decide(RoutingPolicyConfig(splitTunnel = RoutingSplitTunnelMode.ProxySelected))
        )
    }

    @Test
    fun matchesDomainAndDomainSuffixRules() {
        val policy = RoutingPolicyConfig(
            splitTunnel = RoutingSplitTunnelMode.ProxySelected,
            rules = listOf(
                RoutingRuleConfig(
                    type = RoutingRuleType.Domain,
                    value = "api.example.com",
                    action = RoutingRuleAction.Proxy
                ),
                RoutingRuleConfig(
                    type = RoutingRuleType.DomainSuffix,
                    value = "youtube.com",
                    action = RoutingRuleAction.Proxy
                )
            )
        )

        assertEquals(RoutingDecision.Proxy, RoutingPolicyMatcher.decide(policy, host = "API.EXAMPLE.COM."))
        assertEquals(RoutingDecision.Proxy, RoutingPolicyMatcher.decide(policy, host = "www.youtube.com"))
        assertEquals(RoutingDecision.Proxy, RoutingPolicyMatcher.decide(policy, host = "youtube.com"))
        assertEquals(RoutingDecision.Bypass, RoutingPolicyMatcher.decide(policy, host = "notyoutube.com"))
    }

    @Test
    fun matchesIpv4CidrRules() {
        val policy = RoutingPolicyConfig(
            splitTunnel = RoutingSplitTunnelMode.FullTunnel,
            rules = listOf(
                RoutingRuleConfig(
                    type = RoutingRuleType.IpCidr,
                    value = "10.32.0.0/12",
                    action = RoutingRuleAction.Bypass
                )
            )
        )

        assertEquals(RoutingDecision.Bypass, RoutingPolicyMatcher.decide(policy, ip = "10.47.255.1"))
        assertEquals(RoutingDecision.Proxy, RoutingPolicyMatcher.decide(policy, ip = "10.48.0.1"))
        assertEquals(RoutingDecision.Proxy, RoutingPolicyMatcher.decide(policy, ip = "not-an-ip"))
    }

    @Test
    fun leavesGeoRulesForDatResolver() {
        val policy = RoutingPolicyConfig(
            splitTunnel = RoutingSplitTunnelMode.FullTunnel,
            rules = listOf(
                RoutingRuleConfig(
                    type = RoutingRuleType.GeoSite,
                    value = "geosite:youtube",
                    action = RoutingRuleAction.Bypass
                ),
                RoutingRuleConfig(
                    type = RoutingRuleType.GeoIp,
                    value = "geoip:private",
                    action = RoutingRuleAction.Bypass
                )
            )
        )

        assertEquals(RoutingDecision.Proxy, RoutingPolicyMatcher.decide(policy, host = "youtube.com"))
        assertEquals(RoutingDecision.Proxy, RoutingPolicyMatcher.decide(policy, ip = "10.0.0.1"))
    }

    @Test
    fun matchesPrivateGeoIpRulesAsIpv4Cidrs() {
        val policy = RoutingPolicyConfig(
            splitTunnel = RoutingSplitTunnelMode.FullTunnel,
            rules = listOf(
                RoutingRuleConfig(
                    type = RoutingRuleType.GeoIp,
                    value = "geoip:private",
                    action = RoutingRuleAction.Bypass
                )
            )
        )

        assertEquals(RoutingDecision.Bypass, RoutingPolicyMatcher.decide(policy, ip = "10.0.0.1"))
        assertEquals(RoutingDecision.Bypass, RoutingPolicyMatcher.decide(policy, ip = "192.168.1.2"))
        assertEquals(RoutingDecision.Proxy, RoutingPolicyMatcher.decide(policy, ip = "8.8.8.8"))
    }
}
