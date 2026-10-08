package awgbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Установщик из релизов GitHub, который бот пересылает в Telegram (GitHub в РФ открывается не у всех). */
enum class App(val repo: String, val matches: (String) -> Boolean) {
    ANDROID_APK("amnezia-vpn/amneziawg-android", { it.endsWith(".apk") }),
    WINDOWS_MSI("amnezia-vpn/amneziawg-windows-client", { it.endsWith(".msi") && "amd64" in it }),
}

data class AppFile(val name: String, val path: Path, val version: String)

/**
 * Скачивает последнюю версию установщика и держит ее в [cacheDir].
 * Про новый релиз спрашивает GitHub не чаще раза в сутки; если GitHub недоступен - отдает то, что уже скачано.
 */
class Apps(private val cacheDir: Path, private val json: Json) {
    private val log = LoggerFactory.getLogger(Apps::class.java)
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val cached = mutableMapOf<App, AppFile>()
    private val checkedAt = mutableMapOf<App, Instant>()

    @Synchronized
    fun get(app: App): AppFile {
        val current = cached[app]
        if (current != null && checkedAt[app]?.isAfter(Instant.now().minus(RECHECK)) == true) return current
        return try {
            download(app).also {
                cached[app] = it
                checkedAt[app] = Instant.now()
            }
        } catch (e: Exception) {
            log.warn("Could not refresh {}: {}", app, e.message)
            current ?: throw e
        }
    }

    private fun download(app: App): AppFile {
        val release = json.decodeFromString(
            Release.serializer(),
            fetch("https://api.github.com/repos/${app.repo}/releases/latest", HttpResponse.BodyHandlers.ofString()),
        )
        val asset = release.assets.firstOrNull { app.matches(it.name) }
            ?: error("No matching asset in ${app.repo} ${release.tag}")
        Files.createDirectories(cacheDir)
        val path = cacheDir.resolve(asset.name)
        if (!Files.exists(path) || Files.size(path) != asset.size) {
            log.info("Downloading {} {}", asset.name, release.tag)
            val tmp = cacheDir.resolve(asset.name + ".part")
            fetch(asset.url, HttpResponse.BodyHandlers.ofFile(tmp))
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        return AppFile(asset.name, path, release.tag)
    }

    private fun <T> fetch(url: String, handler: HttpResponse.BodyHandler<T>): T {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(3))
            .header("User-Agent", "amnezia-friends-bot")
            .build()
        val response = http.send(request, handler)
        check(response.statusCode() == 200) { "GET $url -> HTTP ${response.statusCode()}" }
        return response.body()
    }

    @Serializable
    private data class Release(@SerialName("tag_name") val tag: String, val assets: List<Asset>)

    @Serializable
    private data class Asset(
        val name: String,
        val size: Long,
        @SerialName("browser_download_url") val url: String,
    )

    private companion object {
        val RECHECK: Duration = Duration.ofDays(1)
    }
}
