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
    fun `blocked config uses bundled routes`() {
        val routes = ClientConfigs.loadBlockedRoutes()
        assertTrue(routes.size > 100)
        assertTrue("1.1.1.0/24" in routes, "DNS 1.1.1.1 должен идти через туннель")
        val text = ClientConfigs(server, routes).build(friend, Mode.BLOCKED)
        assertTrue("AllowedIPs = ${routes.joinToString(", ")}" in text)
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
