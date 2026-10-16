package awgbot

import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Статистика пира из `awg show awg1 dump`. */
data class PeerStats(val lastHandshake: Long, val received: Long, val sent: Long, val endpoint: String? = null) {
    /** IP без порта: порт за NAT оператора меняется сам по себе. */
    val endpointIp: String? get() = endpoint?.substringBeforeLast(':')?.removeSurrounding("[", "]")
}

/**
 * Работа с интерфейсом awg1. Сам бот не root: пиров добавляет и удаляет скрипт-помощник через sudo,
 * и только с проверенными аргументами (ключ и адреса из нашей подсети).
 */
class Awg(private val helper: List<String>) {

    /** Пара ключей Curve25519 в base64, как у `awg genkey` / `awg pubkey`: (private, public). */
    fun generateKeys(): Pair<String, String> {
        val pair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        // PKCS#8 и X.509 у X25519 - фиксированный заголовок плюс 32 байта самого ключа
        val private = pair.private.encoded.takeLast(KEY_SIZE).toByteArray()
        // клэмпинг, как у `awg genkey`; на публичный ключ не влияет - X25519 все равно клэмпит при расчете
        private[0] = (private[0].toInt() and 248).toByte()
        private[31] = ((private[31].toInt() and 127) or 64).toByte()
        val public = pair.public.encoded.takeLast(KEY_SIZE).toByteArray()
        val b64 = Base64.getEncoder()
        return b64.encodeToString(private) to b64.encodeToString(public)
    }

    fun addPeer(publicKey: String, ipv4: String, ipv6: String) {
        run("add", publicKey, ipv4, ipv6)
    }

    fun removePeer(publicKey: String) {
        run("remove", publicKey)
    }

    /** Публичный ключ пира -> статистика. */
    fun stats(): Map<String, PeerStats> =
        run("dump").lines() // строку интерфейса с приватным ключом помощник не отдает
            .map { it.split('\t') }
            .filter { it.size >= 7 }
            .associate { it[0] to PeerStats(it[4].toLong(), it[5].toLong(), it[6].toLong(), it[2].takeUnless { e -> e == "(none)" }) }

    private fun run(vararg args: String): String {
        val process = ProcessBuilder(helper + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "awgbot-peer ${args.first()} timed out" }
        check(process.exitValue() == 0) { "awgbot-peer ${args.first()} failed (${process.exitValue()}): $output" }
        return output
    }

    private companion object {
        const val KEY_SIZE = 32
    }
}

/** Режим конфига: что пускать через VPN. */
enum class Mode(val fileName: String) {
    /** Только заблокированное: YouTube, соцсети, Telegram, Cloudflare и т.д. (routes.txt). */
    BLOCKED("friends.conf"),
    /** Весь трафик, кроме банков, которым нужен российский адрес (all.txt). */
    ALL("friends-all.conf"),
}

/** Собирает клиентский .conf для AmneziaWG. */
class ClientConfigs(
    private val server: ServerParams,
    private val blockedRoutes: List<String>,
    private val allRoutes: List<String> = listOf("0.0.0.0/0", "::/0"),
) {

    fun ipv4(index: Int) = server.ipv4Prefix + index
    fun ipv6(index: Int) = server.ipv6Prefix + index.toString(16)

    fun build(friend: Friend, mode: Mode): String {
        val index = requireNotNull(friend.ipIndex) { "friend ${friend.id} has no address" }
        val allowed = when (mode) {
            Mode.ALL -> allRoutes.joinToString(", ")
            // адреса DNS тоже через туннель, иначе до DNS на сервере не достучаться
            Mode.BLOCKED -> (blockedRoutes + dnsRoutes()).distinct().joinToString(", ")
        }
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${friend.privateKey}")
            appendLine("Address = ${ipv4(index)}/32, ${ipv6(index)}/128")
            appendLine("DNS = ${server.dns}")
            server.obfuscation.forEach { (key, value) -> appendLine("$key = $value") }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${server.publicKey}")
            appendLine("Endpoint = ${server.endpoint}")
            appendLine("AllowedIPs = $allowed")
            appendLine("PersistentKeepalive = 25")
        }
    }

    private fun dnsRoutes() = server.dns.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .map { if (':' in it) "$it/128" else "$it/32" }

    /**
     * Имя файла = имя туннеля в приложении. Android пускает только [a-zA-Z0-9_=+.-] и до 15 символов,
     * поэтому коротко: повторно скачанный «friends_1» тоже должен влезать.
     */
    fun fileName(mode: Mode) = mode.fileName

    companion object {
        fun loadBlockedRoutes(): List<String> = load("routes.txt")

        fun loadAllRoutes(): List<String> = load("all.txt")

        private fun load(name: String): List<String> =
            requireNotNull(ClientConfigs::class.java.getResourceAsStream("/$name")) { "$name is missing" }
                .bufferedReader()
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }
}
