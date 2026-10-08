package awgbot

import awgbot.telegram.Button
import awgbot.telegram.CallbackQuery
import awgbot.telegram.InputFile
import awgbot.telegram.Keyboard
import awgbot.telegram.Message
import awgbot.telegram.TelegramClient
import awgbot.telegram.escapeHtml
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

class FriendsBot(
    private val telegram: TelegramClient,
    private val store: Store,
    private val awg: Awg,
    private val configs: ClientConfigs,
    private val apps: Apps,
    private val config: Config,
) {
    private val log = LoggerFactory.getLogger(FriendsBot::class.java)

    /** file_id уже отправленных установщиков: имя файла -> id, чтобы не грузить 18 МБ каждый раз. */
    private val uploadedApps = mutableMapOf<String, String>()

    fun run() {
        telegram.setCommands(
            mapOf(
                "start" to "Главное меню",
                "config" to "Прислать мой конфиг",
                "guide" to "Инструкция файлом",
                "help" to "Помощь",
            )
        )
        log.info("Bot started, admins: {}", config.adminIds)
        var offset = 0L
        while (true) {
            val updates = try {
                telegram.getUpdates(offset, POLL_TIMEOUT)
            } catch (e: Exception) {
                log.warn("getUpdates failed: {}", e.message)
                Thread.sleep(5_000)
                continue
            }
            for (update in updates) {
                offset = update.updateId + 1
                try {
                    update.message?.let(::onMessage)
                    update.callbackQuery?.let(::onCallback)
                } catch (e: Exception) {
                    log.error("Failed to handle update {}", update.updateId, e)
                }
            }
        }
    }

    // ---------- Сообщения ----------

    private fun onMessage(message: Message) {
        val from = message.from ?: return
        if (message.chat.type != "private") return
        val friend = store.touch(from.id, from.fullName, from.username)
        val chatId = message.chat.id
        val admin = config.isAdmin(from.id)

        when (message.command) {
            "/start", "/menu" -> showHome(chatId, friend)
            "/config" -> if (friend.status == Status.APPROVED) sendConfig(chatId, friend) else showHome(chatId, friend)
            "/guide" -> sendGuide(chatId)
            "/help" -> telegram.sendMessage(chatId, Texts.HELP + if (admin) Texts.ADMIN_HELP else "")
            "/pending" -> if (admin) showPending(chatId) else unknown(chatId)
            "/users" -> if (admin) showUsers(chatId) else unknown(chatId)
            "/revoke" -> if (admin) revokeCommand(chatId, message.argument) else unknown(chatId)
            else -> unknown(chatId)
        }
    }

    private fun unknown(chatId: Long) {
        telegram.sendMessage(chatId, Texts.UNKNOWN)
    }

    // ---------- Кнопки ----------

    private fun onCallback(query: CallbackQuery) {
        val data = query.data ?: return
        val chatId = query.message?.chat?.id ?: return
        val messageId = query.message.messageId
        val friend = store.touch(query.from.id, query.from.fullName, query.from.username)
        telegram.answerCallback(query.id)

        if (data.startsWith("adm:")) {
            if (config.isAdmin(query.from.id)) onAdminButton(chatId, messageId, data)
            return
        }
        val screen = Screen(chatId, messageId)
        when (data) {
            "home" -> showHome(chatId, friend, screen)
            "request" -> requestAccess(chatId, friend, screen)
            "wait" -> whereIsMyConfig(chatId, friend)
            "devices" -> screen.show(Texts.CHOOSE_DEVICE, deviceKeyboard())
            "modes" -> screen.show(Texts.MODES, backKeyboard())
            "trouble" -> screen.show(
                Texts.TROUBLE,
                listOf(listOf(Button("📨 Позвать админа", "help_me")), backRow()),
            )
            "help_me" -> callAdmin(chatId, friend, screen)
            "guide" -> sendGuide(chatId)
            "config" -> if (friend.status == Status.APPROVED) sendConfig(chatId, friend) else showHome(chatId, friend)
            "config_all" -> if (friend.status == Status.APPROVED) sendFullConfig(chatId, friend)

            "dev:ios" -> chooseDevice(friend, Device.IOS, screen, Texts.IOS_INSTALL, listOf(
                listOf(Button("✅ Установилось", "installed")),
                listOf(Button("⛔ Пишет «недоступно в вашей стране»", "ios:region:0")),
                listOf(Button("« Другое устройство", "devices")),
            ))
            "dev:android" -> chooseDevice(friend, Device.ANDROID, screen, Texts.ANDROID_INSTALL, listOf(
                listOf(Button("✅ Установилось", "installed")),
                listOf(Button("📦 Google Play не работает, пришли файл", "apk")),
                listOf(Button("« Другое устройство", "devices")),
            ))
            "dev:windows" -> {
                chooseDevice(friend, Device.WINDOWS, screen, Texts.WINDOWS_INSTALL, emptyList())
                sendApp(chatId, App.WINDOWS_MSI, null, installedKeyboard())
            }
            "dev:mac" -> chooseDevice(friend, Device.MAC, screen, Texts.MAC_INSTALL, listOf(
                listOf(Button("✅ Установилось", "installed")),
                listOf(Button("⛔ Пишет «недоступно в вашей стране»", "ios:region:0")),
                listOf(Button("« Другое устройство", "devices")),
            ))
            "apk" -> sendApp(chatId, App.ANDROID_APK, Texts.ANDROID_APK, installedKeyboard())
            "installed" -> installed(chatId, store.get(friend.id)!!, screen)
            else -> when {
                data.startsWith("ios:region:") -> showRegionStep(screen, data.substringAfterLast(':').toInt())
                data.startsWith("import:") -> screen.show(importText(Device.valueOf(data.substringAfter(':'))), backKeyboard())
            }
        }
    }

    // ---------- Сценарий пользователя ----------

    private fun showHome(chatId: Long, friend: Friend, screen: Screen? = null) {
        val (text, keyboard) = when (friend.status) {
            Status.NEW -> Texts.WELCOME_NEW to listOf(listOf(Button("🔑 Хочу доступ", "request")))
            Status.PENDING -> Texts.STILL_PENDING_REMINDED to pendingKeyboard()
            Status.APPROVED -> Texts.MENU to menuKeyboard()
            Status.REJECTED -> Texts.REJECTED to emptyList()
            Status.REVOKED -> Texts.REVOKED to emptyList()
        }
        (screen ?: Screen(chatId, null)).show(text, keyboard)
    }

    private fun requestAccess(chatId: Long, friend: Friend, screen: Screen) {
        if (friend.status != Status.NEW) return showHome(chatId, friend, screen)
        store.requestAccess(friend.id, now())
        screen.show(Texts.REQUEST_SENT, deviceKeyboard(withBack = false))
        notifyAdmins(
            "🆕 <b>Заявка на доступ</b>\n${friend.title.escapeHtml()}\nid <code>${friend.id}</code>",
            decisionKeyboard(friend.id),
        )
    }

    /** "Где мой конфиг?": успокаиваем и напоминаем админу, но не чаще раза в [REMIND_EVERY]. */
    private fun whereIsMyConfig(chatId: Long, friend: Friend) {
        if (friend.status != Status.PENDING) return showHome(chatId, friend)
        val last = friend.lastReminderAt ?: 0
        if (now() - last >= REMIND_EVERY.seconds) {
            store.setLastReminder(friend.id, now())
            notifyAdmins(
                "⏰ <b>Ждёт одобрения</b> уже ${since(friend.requestedAt)}\n${friend.title.escapeHtml()}\nid <code>${friend.id}</code>",
                decisionKeyboard(friend.id),
            )
            telegram.sendMessage(chatId, Texts.STILL_PENDING, pendingKeyboard())
        } else {
            telegram.sendMessage(chatId, Texts.STILL_PENDING_REMINDED, pendingKeyboard())
        }
    }

    private fun chooseDevice(friend: Friend, device: Device, screen: Screen, text: String, keyboard: Keyboard) {
        store.setDevice(friend.id, device)
        screen.show(text, keyboard)
    }

    private fun showRegionStep(screen: Screen, step: Int) {
        val steps = Texts.IOS_REGION_STEPS
        val index = step.coerceIn(0, steps.lastIndex)
        val keyboard = buildList {
            if (index < steps.lastIndex) {
                add(listOf(Button(if (index == 0) "Поехали 👉" else "Готово, дальше 👉", "ios:region:${index + 1}")))
            } else {
                add(listOf(Button("✅ Установилось", "installed")))
            }
            if (index > 0) add(listOf(Button("« Назад", "ios:region:${index - 1}")))
        }
        screen.show(steps[index], keyboard)
    }

    private fun installed(chatId: Long, friend: Friend, screen: Screen) {
        when (friend.status) {
            Status.APPROVED -> {
                screen.show("👍 Отлично, приложение стоит! Теперь добавим конфиг.", emptyList())
                sendConfig(chatId, friend)
            }
            Status.PENDING -> screen.show(Texts.WAIT_AFTER_INSTALL, pendingKeyboard())
            else -> showHome(chatId, friend, screen)
        }
    }

    private fun callAdmin(chatId: Long, friend: Friend, screen: Screen) {
        val stats = friend.publicKey?.let { runCatching { awg.stats()[it] }.getOrNull() }
        notifyAdmins(
            "🆘 <b>Не работает VPN</b>\n${friend.title.escapeHtml()} (id <code>${friend.id}</code>)\n" +
                "Устройство: ${friend.device?.title ?: "не выбрано"}\n" +
                "Последнее подключение: ${stats?.let { ago(it.lastHandshake.takeIf { h -> h > 0 }) } ?: "никогда"}" +
                (friend.username?.let { "\nНаписать: @$it" } ?: "\nНаписать: <a href=\"tg://user?id=${friend.id}\">профиль</a>"),
            emptyList(),
        )
        screen.show(Texts.TROUBLE_SENT, backKeyboard())
    }

    // ---------- Файлы ----------

    /** Основной конфиг ("только заблокированное") + кнопки за вторым режимом и инструкцией. */
    private fun sendConfig(chatId: Long, friend: Friend) {
        telegram.sendChatAction(chatId, "upload_document")
        val device = friend.device
        val keyboard = buildList {
            add(listOf(Button("🌍 Ещё конфиг «весь трафик»", "config_all")))
            if (device != null) add(listOf(Button("📖 Как добавить (${device.title})", "import:${device.name}")))
            add(listOf(Button("📲 Как добавить на другое устройство", "devices")))
            add(listOf(Button("« Меню", "home")))
        }
        telegram.sendDocument(
            chatId,
            InputFile.Upload(configs.fileName(Mode.BLOCKED), configs.build(friend, Mode.BLOCKED).toByteArray()),
            Texts.CONFIG_BLOCKED_CAPTION,
        )
        if (device != null) telegram.sendMessage(chatId, importText(device), keyboard)
        else telegram.sendMessage(chatId, "Куда будете ставить? Покажу, как добавить конфиг 👇", deviceKeyboard() + listOf(keyboard.first()))
    }

    private fun sendFullConfig(chatId: Long, friend: Friend) {
        val text = configs.build(friend, Mode.ALL)
        telegram.sendDocument(chatId, InputFile.Upload(configs.fileName(Mode.ALL), text.toByteArray()), Texts.CONFIG_ALL_CAPTION)
        telegram.sendPhoto(chatId, InputFile.Upload("friends-all.png", Qr.png(text)), Texts.QR_CAPTION, backKeyboard())
    }

    private fun sendApp(chatId: Long, app: App, captionHtml: String?, keyboard: Keyboard) {
        telegram.sendChatAction(chatId, "upload_document")
        val file = try {
            apps.get(app)
        } catch (e: Exception) {
            log.error("Could not get {}", app, e)
            telegram.sendMessage(chatId, "😔 Не получилось скачать установщик, попробуйте позже или нажмите «Позвать админа».", backKeyboard())
            return
        }
        val fileId = uploadedApps[file.name]
        val input = fileId?.let { InputFile.Existing(it) } ?: InputFile.Upload(file.name, file.path.toFile().readBytes())
        val caption = (captionHtml ?: "") + "\n\nВерсия ${file.version.escapeHtml()}"
        val sentId = telegram.sendDocument(chatId, input, caption.trim(), keyboard)
        if (sentId.isNotEmpty()) uploadedApps[file.name] = sentId
    }

    private fun sendGuide(chatId: Long) {
        telegram.sendDocument(chatId, InputFile.Upload("VPN-instrukciya.html", Guide.html().toByteArray()), Texts.GUIDE_CAPTION)
    }

    private fun importText(device: Device) = when (device) {
        Device.IOS -> Texts.IOS_IMPORT
        Device.ANDROID -> Texts.ANDROID_IMPORT
        Device.WINDOWS -> Texts.WINDOWS_IMPORT
        Device.MAC -> Texts.MAC_IMPORT
    }

    // ---------- Админ ----------

    private fun onAdminButton(chatId: Long, messageId: Long, data: String) {
        val (_, action, idText) = data.split(':')
        val friend = store.get(idText.toLong()) ?: return
        val result = when (action) {
            "ok" -> approve(friend)
            "no" -> reject(friend)
            "rv" -> revoke(friend)
            else -> return
        }
        telegram.editMessage(chatId, messageId, result)
    }

    private fun approve(friend: Friend): String {
        if (friend.status == Status.APPROVED) return "Уже одобрено: ${friend.title.escapeHtml()}"
        val index = (FIRST_IP..LAST_IP).firstOrNull { it !in store.usedIpIndexes() }
            ?: return "❌ Кончились адреса в подсети, некуда добавить ${friend.title.escapeHtml()}"
        val (privateKey, publicKey) = awg.generateKeys()
        try {
            awg.addPeer(publicKey, configs.ipv4(index), configs.ipv6(index))
        } catch (e: Exception) {
            log.error("addPeer failed for {}", friend.id, e)
            return "❌ Не удалось добавить пира для ${friend.title.escapeHtml()}: ${e.message?.escapeHtml()}"
        }
        store.approve(friend.id, index, privateKey, publicKey)
        val approved = store.get(friend.id)!!
        try {
            telegram.sendMessage(friend.id, Texts.APPROVED)
            sendConfig(friend.id, approved)
        } catch (e: Exception) {
            log.warn("Could not send config to {}: {}", friend.id, e.message)
            return "⚠️ Одобрено, но конфиг отправить не удалось (бот заблокирован?): ${friend.title.escapeHtml()}"
        }
        return "✅ Одобрено: ${friend.title.escapeHtml()}, адрес ${configs.ipv4(index)}"
    }

    private fun reject(friend: Friend): String {
        if (friend.status != Status.PENDING) return "Заявки уже нет: ${friend.title.escapeHtml()} (${friend.status})"
        store.setStatus(friend.id, Status.REJECTED)
        telegram.sendMessage(friend.id, Texts.REJECTED)
        return "🚫 Отклонено: ${friend.title.escapeHtml()}"
    }

    private fun revoke(friend: Friend): String {
        if (friend.status != Status.APPROVED) return "Доступа и так нет: ${friend.title.escapeHtml()}"
        friend.publicKey?.let(awg::removePeer)
        store.revoke(friend.id)
        telegram.sendMessage(friend.id, Texts.REVOKED)
        return "🔒 Доступ отключён: ${friend.title.escapeHtml()}"
    }

    private fun revokeCommand(chatId: Long, argument: String) {
        val friend = argument.toLongOrNull()?.let(store::get)
            ?: return telegram.sendMessage(chatId, "Укажите id: /revoke 123456. Id есть в /users").let {}
        telegram.sendMessage(chatId, revoke(friend))
    }

    private fun showPending(chatId: Long) {
        val pending = store.list(Status.PENDING)
        if (pending.isEmpty()) return telegram.sendMessage(chatId, "Заявок нет 🎉").let {}
        for (friend in pending) {
            telegram.sendMessage(
                chatId,
                "🕐 ${friend.title.escapeHtml()}\nid <code>${friend.id}</code>, ждёт ${since(friend.requestedAt)}",
                decisionKeyboard(friend.id),
            )
        }
    }

    private fun showUsers(chatId: Long) {
        val friends = store.list(Status.APPROVED)
        if (friends.isEmpty()) return telegram.sendMessage(chatId, "Пока ни у кого нет доступа").let {}
        val stats = runCatching { awg.stats() }.getOrElse {
            log.warn("stats failed: {}", it.message)
            emptyMap()
        }
        val lines = friends.map { friend ->
            val peer = stats[friend.publicKey]
            val seen = peer?.lastHandshake?.takeIf { it > 0 }?.let { ago(it) } ?: "ни разу"
            val traffic = peer?.let { " · ↓${mb(it.sent)} ↑${mb(it.received)}" } ?: ""
            "• ${friend.title.escapeHtml()}\n   <code>${friend.id}</code> · ${configs.ipv4(friend.ipIndex!!)} · " +
                "${friend.device?.title ?: "?"} · был ${seen}$traffic"
        }
        telegram.sendMessage(chatId, "👥 <b>Доступ есть у ${friends.size}</b>\n\n" + lines.joinToString("\n"))
    }

    private fun notifyAdmins(html: String, keyboard: Keyboard) {
        for (admin in config.adminIds) {
            try {
                telegram.sendMessage(admin, html, keyboard)
            } catch (e: Exception) {
                log.warn("Could not notify admin {}: {}", admin, e.message)
            }
        }
    }

    // ---------- Клавиатуры ----------

    private fun menuKeyboard(): Keyboard = listOf(
        listOf(Button("📄 Мой конфиг", "config")),
        listOf(Button("📲 Установить приложение", "devices")),
        listOf(Button("🔀 Режимы работы", "modes"), Button("📘 Инструкция", "guide")),
        listOf(Button("🆘 Не работает", "trouble")),
    )

    private fun pendingKeyboard(): Keyboard = listOf(
        listOf(Button("📲 Установить приложение", "devices")),
        listOf(Button("⏳ Где мой конфиг?", "wait")),
    )

    private fun deviceKeyboard(withBack: Boolean = true): Keyboard = buildList {
        add(listOf(Button("🍏 iPhone / iPad", "dev:ios"), Button("🤖 Android", "dev:android")))
        add(listOf(Button("💻 Windows", "dev:windows"), Button("🍎 Mac", "dev:mac")))
        if (withBack) add(backRow())
    }

    private fun installedKeyboard(): Keyboard = listOf(
        listOf(Button("✅ Установилось", "installed")),
        listOf(Button("« Другое устройство", "devices")),
    )

    private fun decisionKeyboard(friendId: Long): Keyboard = listOf(
        listOf(Button("✅ Одобрить", "adm:ok:$friendId"), Button("❌ Отклонить", "adm:no:$friendId")),
    )

    private fun backRow() = listOf(Button("« Меню", "home"))
    private fun backKeyboard(): Keyboard = listOf(backRow())

    /** Экран, который по возможности перерисовывается на месте, чтобы чат не захламлялся. */
    private inner class Screen(val chatId: Long, val messageId: Long?) {
        fun show(html: String, keyboard: Keyboard) {
            if (messageId != null) {
                try {
                    telegram.editMessage(chatId, messageId, html, keyboard)
                    return
                } catch (e: Exception) {
                    // сообщение с файлом или слишком старое не редактируется - просто пришлем новое
                    if (e.message?.contains("message is not modified") == true) return
                }
            }
            telegram.sendMessage(chatId, html, keyboard)
        }
    }

    private companion object {
        const val POLL_TIMEOUT = 50
        const val FIRST_IP = 2
        const val LAST_IP = 254
        val REMIND_EVERY: Duration = Duration.ofHours(3)

        fun now() = Instant.now().epochSecond

        /** Сколько прошло: "5 мин", "3 ч", "2 дн". */
        fun since(epochSeconds: Long?): String {
            val minutes = (now() - (epochSeconds ?: now())) / 60
            return when {
                minutes < 60 -> "$minutes мин"
                minutes < 60 * 24 -> "${minutes / 60} ч"
                else -> "${minutes / 60 / 24} дн"
            }
        }

        fun ago(epochSeconds: Long?): String = when {
            epochSeconds == null -> "никогда"
            now() - epochSeconds < 60 -> "только что"
            else -> since(epochSeconds) + " назад"
        }

        fun mb(bytes: Long) = "%.0f МБ".format(bytes / 1_048_576.0)
    }
}
