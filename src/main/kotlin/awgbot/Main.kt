package awgbot

import awgbot.telegram.TelegramClient
import kotlinx.serialization.json.Json
import java.nio.file.Path

fun main() {
    val config = Config.fromEnv()
    val json = Json { ignoreUnknownKeys = true }
    FriendsBot(
        telegram = TelegramClient(config.telegramToken, json),
        store = Store(config.databasePath),
        awg = Awg(config.peerHelper),
        configs = ClientConfigs(config.server, ClientConfigs.loadBlockedRoutes(), ClientConfigs.loadAllRoutes()),
        apps = Apps(Path.of(config.cacheDir), json),
        config = config,
        load = ServerLoad(config.netIface),
    ).run()
}
