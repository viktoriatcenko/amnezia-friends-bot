package awgbot

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientConfigsTest {
    private val server = ServerParams(
        endpoint = "1.2.3.4:51820",
        publicKey = "server-pub",
        obfuscation = listOf("Jc" to "4", "H1" to "123"),
        ipv4Prefix = "10.9.0.",
        ipv6Prefix = "fd09:9::",
        dns = "1.1.1.1",
    )
    private val friend = Friend(1, "Вася", null, Status.APPROVED, Device.IOS, 26, "priv", "pub", 0, 0)

    @Test
    fun `full config routes everything`() {
        val text = ClientConfigs(server, listOf("8.8.8.0/24")).build(friend, Mode.ALL)
        assertTrue("Address = 10.9.0.26/32, fd09:9::1a/128" in text)
        assertTrue("AllowedIPs = 0.0.0.0/0, ::/0" in text)
        assertTrue("Jc = 4\nH1 = 123" in text)
    }

    @Test
    fun `full config skips t-bank`() {
        val routes = ClientConfigs.loadAllRoutes()
        val text = ClientConfigs(server, emptyList(), routes).build(friend, Mode.ALL)
        assertTrue("AllowedIPs = ${routes.joinToString(", ")}" in text)
        assertTrue("::/0" in routes)
        assertTrue("0.0.0.0/2" in routes)
        assertTrue("178.130.128.0/23" !in routes, "Т-Банк должен идти мимо VPN")
        assertTrue(ClientConfigs.loadBlockedRoutes().none { it.startsWith("178.130.128.") })
    }

    @Test
    fun `blocked config uses bundled routes`() {
        val routes = ClientConfigs.loadBlockedRoutes()
        assertTrue(routes.size > 100)
        assertTrue("1.1.1.0/24" in routes, "DNS 1.1.1.1 должен идти через туннель")
        val text = ClientConfigs(server, routes).build(friend, Mode.BLOCKED)
        assertTrue("AllowedIPs = ${routes.joinToString(", ")}" in text)
    }

    @Test
    fun `blocked config routes server dns through tunnel`() {
        val text = ClientConfigs(server.copy(dns = "10.9.0.1, fd09:9::1"), listOf("8.8.8.0/24")).build(friend, Mode.BLOCKED)
        assertTrue("DNS = 10.9.0.1, fd09:9::1" in text)
        assertTrue("AllowedIPs = 8.8.8.0/24, 10.9.0.1/32, fd09:9::1/128" in text)
    }

    @Test
    fun `generated keys are 32 bytes`() {
        val (private, public) = Awg(emptyList()).generateKeys()
        assertEquals(32, Base64.getDecoder().decode(private).size)
        assertEquals(32, Base64.getDecoder().decode(public).size)
    }

    @Test
    fun `guide renders all region steps`() {
        val html = Guide.html()
        Texts.IOS_REGION_STEPS.forEach { step -> assertTrue(step.lines().first().trim() in html) }
    }
}
