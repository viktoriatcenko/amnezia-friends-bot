package awgbot.telegram

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

class TelegramClient(token: String, private val json: Json) {
    private val baseUrl = "https://api.telegram.org/bot$token"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun getUpdates(offset: Long, timeoutSeconds: Int): List<Update> {
        val body = buildJsonObject {
            put("offset", offset)
            put("timeout", timeoutSeconds)
            putJsonArray("allowed_updates") {
                add("message")
                add("callback_query")
            }
        }
        return call("getUpdates", body, Duration.ofSeconds(timeoutSeconds + 10L), ListSerializer(Update.serializer()))
    }

    /** Отправляет текст с HTML-разметкой. [keyboard] - ряды кнопок под сообщением. */
    fun sendMessage(chatId: Long, html: String, keyboard: Keyboard = emptyList()): Long {
        val body = buildJsonObject {
            put("chat_id", chatId)
            put("text", html)
            put("parse_mode", "HTML")
            putJsonObject("link_preview_options") { put("is_disabled", true) }
            if (keyboard.isNotEmpty()) put("reply_markup", markup(keyboard))
        }
        return call("sendMessage", body, Duration.ofSeconds(30), Message.serializer()).messageId
    }

    /** Меняет текст сообщения; кнопки остаются, только если переданы в [keyboard], иначе убираются. */
    fun editMessage(chatId: Long, messageId: Long, html: String, keyboard: Keyboard = emptyList()) {
        val body = buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", html)
            put("parse_mode", "HTML")
            putJsonObject("link_preview_options") { put("is_disabled", true) }
            if (keyboard.isNotEmpty()) put("reply_markup", markup(keyboard))
        }
        call("editMessageText", body, Duration.ofSeconds(30), JsonElement.serializer())
    }

    /** Ответ на нажатие кнопки; без [text] просто гасит часики на кнопке. */
    fun answerCallback(callbackId: String, text: String? = null) {
        call("answerCallbackQuery", buildJsonObject {
            put("callback_query_id", callbackId)
            text?.let { put("text", it) }
        }, Duration.ofSeconds(30), JsonElement.serializer())
    }

    /** "Отправляет файл..." в шапке чата, пока бот готовит что-то долгое. */
    fun sendChatAction(chatId: Long, action: String) {
        call("sendChatAction", buildJsonObject {
            put("chat_id", chatId)
            put("action", action)
        }, Duration.ofSeconds(30), JsonElement.serializer())
    }

    /**
     * Отправляет файл. [file] - либо содержимое с именем, либо file_id уже загруженного в Telegram файла.
     * Возвращает file_id, чтобы большие файлы (APK) не загружать заново каждый раз.
     */
    fun sendDocument(chatId: Long, file: InputFile, captionHtml: String? = null, keyboard: Keyboard = emptyList()): String =
        sendFile("sendDocument", "document", chatId, file, captionHtml, keyboard).document?.fileId.orEmpty()

    fun sendPhoto(chatId: Long, file: InputFile, captionHtml: String? = null, keyboard: Keyboard = emptyList()) {
        sendFile("sendPhoto", "photo", chatId, file, captionHtml, keyboard)
    }

    /** Меню команд по кнопке "/" в личке с ботом. */
    fun setCommands(commands: Map<String, String>) {
        val body = buildJsonObject {
            putJsonArray("commands") {
                for ((command, description) in commands) {
                    addJsonObject {
                        put("command", command)
                        put("description", description)
                    }
                }
            }
        }
        call("setMyCommands", body, Duration.ofSeconds(30), JsonElement.serializer())
    }

    private fun sendFile(
        method: String,
        field: String,
        chatId: Long,
        file: InputFile,
        captionHtml: String?,
        keyboard: Keyboard,
    ): Message {
        val boundary = "awgbot-" + UUID.randomUUID()
        val body = ByteArrayOutputStream()
        fun part(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        part("chat_id", chatId.toString())
        captionHtml?.let {
            part("caption", it.take(CAPTION_LIMIT))
            part("parse_mode", "HTML")
        }
        if (keyboard.isNotEmpty()) part("reply_markup", markup(keyboard).toString())
        when (file) {
            is InputFile.Existing -> part(field, file.fileId)
            is InputFile.Upload -> {
                body.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"${file.name}\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
                )
                body.write(file.bytes)
                body.write("\r\n".toByteArray())
            }
        }
        body.write("--$boundary--\r\n".toByteArray())

        val request = HttpRequest.newBuilder(URI.create("$baseUrl/$method"))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build()
        return parse(method, http.send(request, HttpResponse.BodyHandlers.ofString()), Message.serializer())
    }

    private fun <T> call(method: String, body: JsonObject, timeout: Duration, resultSerializer: KSerializer<T>): T {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/$method"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return parse(method, http.send(request, HttpResponse.BodyHandlers.ofString()), resultSerializer)
    }

    private fun <T> parse(method: String, response: HttpResponse<String>, resultSerializer: KSerializer<T>): T {
        val parsed = json.decodeFromString(TelegramResponse.serializer(resultSerializer), response.body())
        return parsed.result.takeIf { parsed.ok }
            ?: throw TelegramException("Telegram $method failed: HTTP ${response.statusCode()}, ${parsed.description}")
    }

    private fun markup(keyboard: Keyboard): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            for (row in keyboard) {
                add(buildJsonArray {
                    for (button in row) {
                        addJsonObject {
                            put("text", button.text)
                            if (button.url != null) put("url", button.url) else put("callback_data", button.data)
                        }
                    }
                })
            }
        }
    }

    private companion object {
        const val CAPTION_LIMIT = 1024
    }
}

class TelegramException(message: String) : RuntimeException(message)

/** Экранирует текст для parse_mode=HTML. */
fun String.escapeHtml(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Кнопка под сообщением: либо [data] приходит боту при нажатии (до 64 байт), либо открывается [url]. */
data class Button(val text: String, val data: String? = null, val url: String? = null)

/** Ряды кнопок сверху вниз. */
typealias Keyboard = List<List<Button>>

sealed interface InputFile {
    class Upload(val name: String, val bytes: ByteArray) : InputFile
    class Existing(val fileId: String) : InputFile
}

@Serializable
data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
)

@Serializable
data class Update(
    @SerialName("update_id") val updateId: Long,
    val message: Message? = null,
    @SerialName("callback_query") val callbackQuery: CallbackQuery? = null,
)

@Serializable
data class Message(
    @SerialName("message_id") val messageId: Long,
    val chat: Chat,
    val from: User? = null,
    val text: String? = null,
    val document: Document? = null,
) {
    /** Команда без @имени_бота, например "/start"; null, если сообщение не команда. */
    val command: String?
        get() = text?.takeIf { it.startsWith("/") }?.substringBefore(' ')?.substringBefore('@')

    /** Текст после команды: "/revoke 123" -> "123". */
    val argument: String
        get() = text?.substringAfter(' ', "")?.trim().orEmpty()
}

@Serializable
data class Document(@SerialName("file_id") val fileId: String)

@Serializable
data class Chat(val id: Long, val type: String)

@Serializable
data class User(
    val id: Long,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
) {
    val fullName: String
        get() = listOfNotNull(firstName, lastName).joinToString(" ")
}

/** Нажатие кнопки под сообщением бота. */
@Serializable
data class CallbackQuery(
    val id: String,
    val from: User,
    val message: Message? = null,
    val data: String? = null,
)
