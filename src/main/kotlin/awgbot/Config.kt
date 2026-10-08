package awgbot

data class Config(
    val telegramToken: String,
    /** Кто одобряет заявки и видит /users; остальные - обычные пользователи. */
    val adminIds: Set<Long>,
    val databasePath: String,
    /** Куда складывать скачанные APK/MSI, чтобы не тянуть их с GitHub на каждый запрос. */
    val cacheDir: String,
    /** Куда сохранять скриншоты, которые админ присылает боту (для картинок в инструкции). */
    val inboxDir: String,
    /** Внешний интерфейс сервера и его канал: для экрана «Сервер» в админке. */
    val netIface: String,
    val channelMbit: Int,
    /** Команда, через которую бот (не root) добавляет и удаляет пиров, см. deploy/awgbot-peer. */
    val peerHelper: List<String>,
    val server: ServerParams,
) {
    fun isAdmin(userId: Long): Boolean = userId in adminIds

    companion object {
        fun fromEnv(): Config = Config(
            telegramToken = required("TELEGRAM_BOT_TOKEN"),
            adminIds = optional("ADMIN_IDS", "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.toLong() }
                .toSet(),
            databasePath = optional("DATABASE_PATH", "data/awgbot.db"),
            cacheDir = optional("CACHE_DIR", "data/cache"),
            inboxDir = optional("INBOX_DIR", "data/inbox"),
            netIface = optional("NET_IFACE", "eth0"),
            channelMbit = optional("CHANNEL_MBIT", "300").toInt(),
            peerHelper = optional("PEER_HELPER", "sudo -n /usr/local/sbin/awgbot-peer").split(" ").filter { it.isNotEmpty() },
            server = ServerParams(
                endpoint = required("AWG_ENDPOINT"),
                publicKey = required("AWG_SERVER_PUBLIC_KEY"),
                obfuscation = required("AWG_OBFUSCATION")
                    .split(";")
                    .filter { it.isNotBlank() }
                    .map { it.substringBefore('=').trim() to it.substringAfter('=').trim() },
                ipv4Prefix = optional("AWG_IPV4_PREFIX", "10.9.0."),
                ipv6Prefix = optional("AWG_IPV6_PREFIX", "fd09:9::"),
                dns = optional("AWG_DNS", "1.1.1.1, 2606:4700:4700::1111"),
            ),
        )

        private fun required(name: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: error("Environment variable $name is not set")

        private fun optional(name: String, default: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    }
}

/** Публичные параметры сервера awg1, которые попадают в клиентский конфиг. */
data class ServerParams(
    /** host:port, куда подключаются клиенты. */
    val endpoint: String,
    val publicKey: String,
    /** Jc, Jmin, Jmax, S1, S2, H1-H4 - обязаны совпадать с серверными, иначе рукопожатие не пройдет. */
    val obfuscation: List<Pair<String, String>>,
    /** Адрес клиента N - это ipv4Prefix + N и ipv6Prefix + N (в hex). */
    val ipv4Prefix: String,
    val ipv6Prefix: String,
    val dns: String,
)
