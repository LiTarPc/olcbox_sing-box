package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class SingboxConfigTest {

    @Test
    fun testValidJsonGenerated() {
        val config = SingboxConfigGenerator.generate()
        val json = Json.parseToJsonElement(config)
        assertIs<JsonObject>(json)
    }

    @Test
    fun testConfigContainsRequiredSections() {
        val json = Json.parseToJsonElement(
            SingboxConfigGenerator.generate()
        ).jsonObject

        assertNotNull(json["log"])
        assertNotNull(json["dns"])
        assertNotNull(json["inbounds"])
        assertNotNull(json["outbounds"])
        assertNotNull(json["route"])
    }

    @Test
    fun testTunInboundConfiguredCorrectly() {
        val json = Json.parseToJsonElement(
            SingboxConfigGenerator.generate()
        ).jsonObject

        val inbounds = json["inbounds"]!!.jsonArray
        val tun = inbounds.first { it.jsonObject["type"]?.jsonPrimitive?.content == "tun" }
        assertEquals("Olcbox", tun.jsonObject["interface_name"]?.jsonPrimitive?.content)
        assertEquals("198.18.0.1/15",
            tun.jsonObject["address"]!!.jsonArray.first().jsonPrimitive.content)
        assertEquals(true, tun.jsonObject["auto_route"]?.jsonPrimitive?.boolean)
        assertEquals(true, tun.jsonObject["strict_route"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun testSocksOutboundHasUdpOverTcp() {
        val json = Json.parseToJsonElement(
            SingboxConfigGenerator.generate(socksPort = 10808)
        ).jsonObject

        val outbounds = json["outbounds"]!!.jsonArray
        val socks = outbounds.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "socks"
        }.jsonObject

        assertEquals("127.0.0.1", socks["server"]?.jsonPrimitive?.content)
        assertEquals(10808, socks["server_port"]?.jsonPrimitive?.int)
        val uot = socks["udp_over_tcp"]?.jsonObject
        assertNotNull(uot)
        assertEquals(true, uot["enabled"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun testBypassRuEnabledByDefault() {
        val json = Json.parseToJsonElement(
            SingboxConfigGenerator.generate(bypassRuEnabled = true)
        ).jsonObject

        val routeRules = json["route"]!!.jsonObject["rules"]!!.jsonArray
        val hasRuBypass = routeRules.any { rule ->
            rule.jsonObject["domain_suffix"]?.jsonArray
                ?.any { it.jsonPrimitive.content == ".ru" } == true
        }
        assertTrue(hasRuBypass, "Should contain .ru bypass rule")
    }

    @Test
    fun testBypassRuCanBeDisabled() {
        val json = Json.parseToJsonElement(
            SingboxConfigGenerator.generate(bypassRuEnabled = false)
        ).jsonObject

        val routeRules = json["route"]!!.jsonObject["rules"]!!.jsonArray
        val hasRuBypass = routeRules.any { rule ->
            rule.jsonObject["domain_suffix"]?.jsonArray
                ?.any { it.jsonPrimitive.content == ".ru" } == true
        }
        assertFalse(hasRuBypass, "Should not contain .ru bypass rule when disabled")
    }

    @Test
    fun testUserDomainsAddedToRules() {
        val json = Json.parseToJsonElement(
            SingboxConfigGenerator.generate(
                extraBypassDomains = listOf("example.com", "test.org")
            )
        ).jsonObject

        val routeRules = json["route"]!!.jsonObject["rules"]!!.jsonArray
        val hasUserDomains = routeRules.any { rule ->
            rule.jsonObject["domain"]?.jsonArray
                ?.any { it.jsonPrimitive.content == "example.com" } == true
        }
        assertTrue(hasUserDomains)
    }

    @Test
    fun testUserCidrsAddedToRules() {
        val json = Json.parseToJsonElement(
            SingboxConfigGenerator.generate(
                extraBypassCidrs = listOf("10.10.0.0/16")
            )
        ).jsonObject

        val routeRules = json["route"]!!.jsonObject["rules"]!!.jsonArray
        val hasCidr = routeRules.any { rule ->
            rule.jsonObject["ip_cidr"]?.jsonArray
                ?.any { it.jsonPrimitive.content == "10.10.0.0/16" } == true
        }
        assertTrue(hasCidr)
    }

    @Test
    fun testFinalRouteIsProxyOut() {
        val json = Json.parseToJsonElement(
            SingboxConfigGenerator.generate()
        ).jsonObject
        assertEquals("proxy-out",
            json["route"]!!.jsonObject["final"]?.jsonPrimitive?.content)
    }
}
