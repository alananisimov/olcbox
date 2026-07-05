package org.olcbox.app.data.routing

import org.olcbox.app.data.model.RoutingDatListConfig
import org.olcbox.app.data.model.RoutingPolicyConfig
import org.olcbox.app.data.model.RoutingRuleAction
import org.olcbox.app.data.model.RoutingRuleConfig
import org.olcbox.app.data.model.RoutingRuleType
import org.olcbox.app.data.model.RoutingSplitTunnelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutingPolicyPlannerTest {
    @Test
    fun emptyPolicyProducesEmptyPlan() {
        val plan = RoutingPolicyPlanner.plan(null)

        assertFalse(plan.hasPolicy)
        assertEquals("Routing policy: none", plan.summary())
    }

    @Test
    fun separatesRouteLevelAndResolverRules() {
        val plan = RoutingPolicyPlanner.plan(
            RoutingPolicyConfig(
                splitTunnel = RoutingSplitTunnelMode.BypassSelected,
                datLists = listOf(
                    RoutingDatListConfig(
                        name = "geosite",
                        url = "https://example.test/geosite.dat",
                        categories = listOf("geosite:ru", "geosite:youtube")
                    )
                ),
                rules = listOf(
                    RoutingRuleConfig(
                        type = RoutingRuleType.IpCidr,
                        value = "10.0.0.0/8",
                        action = RoutingRuleAction.Bypass
                    ),
                    RoutingRuleConfig(
                        type = RoutingRuleType.IpCidr,
                        value = "203.0.113.0/24",
                        action = RoutingRuleAction.Proxy
                    ),
                    RoutingRuleConfig(
                        type = RoutingRuleType.DomainSuffix,
                        value = "youtube.com",
                        action = RoutingRuleAction.Proxy
                    ),
                    RoutingRuleConfig(
                        type = RoutingRuleType.GeoSite,
                        value = "geosite:vk",
                        action = RoutingRuleAction.Bypass
                    ),
                    RoutingRuleConfig(
                        type = RoutingRuleType.GeoIp,
                        value = "geoip:private",
                        action = RoutingRuleAction.Bypass
                    ),
                    RoutingRuleConfig(
                        type = RoutingRuleType.IpCidr,
                        value = "not-a-cidr",
                        action = RoutingRuleAction.Bypass
                    )
                )
            )
        )

        assertTrue(plan.hasPolicy)
        assertTrue(plan.hasRouteLevelRules)
        assertTrue(plan.hasResolverRules)
        assertTrue(plan.hasMapDnsRules)
        assertEquals(listOf("203.0.113.0/24"), plan.proxyIpv4Cidrs.map { it.value })
        assertEquals(
            listOf(
                "10.0.0.0/8",
                "0.0.0.0/8",
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
            ),
            plan.bypassIpv4Cidrs.map { it.value }
        )
        assertEquals(listOf("youtube.com"), plan.proxyDomainRules.map { it.value })
        assertEquals(listOf("geosite:vk"), plan.bypassDomainRules.map { it.value })
        assertEquals(listOf("geosite:ru", "geosite:youtube"), plan.proxyDatCategories)
        assertEquals(emptyList(), plan.bypassDatCategories)
        assertEquals(1, plan.domainRuleCount)
        assertEquals(2, plan.geoRuleCount)
        assertEquals(1, plan.datListCount)
        assertEquals(2, plan.datCategoryCount)
        assertEquals(1, plan.unsupportedRuleCount)
    }
}
