package awgbot

import awgbot.telegram.Button
import awgbot.telegram.CallbackQuery
import awgbot.telegram.InputFile
import awgbot.telegram.Keyboard
import awgbot.telegram.Message
import awgbot.telegram.TelegramClient
import awgbot.telegram.escapeHtml
import org.slf4j.LoggerFactory
import java.io.File
import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /** Кто нажал "Нужна помощь": следующее сообщение уйдёт админу. Живёт в памяти, после перезапуска - заново. */
    private val awaitingHelp = mutableSetOf<Long>()

    /** Админ нажал "Ответить": его следующее сообщение уйдёт этому пользователю. */
    private val replyingTo = mutableMapOf<Long, Long>()

    private val botUsername by lazy { telegram.botUsername() }

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
        startMonitor()
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

        if (!admin && !allowed(friend)) {
            if (message.command == "/start" && message.argument == inviteCode()) {
                store.setInvited(friend.id, now())
                showHome(chatId, store.get(friend.id)!!)
            } else {
                telegram.sendMessage(chatId, Texts.CLOSED)
            }
            return
        }
        if (admin && message.command == null) replyingTo.remove(from.id)?.let { return relayToFriend(chatId, it, message) }
        if (message.command == null && awaitingHelp.remove(from.id)) return relayToAdmins(friend, message)
        if (admin && (message.photo != null || message.document != null)) return saveScreenshot(chatId, message)
        if (message.command == null && message.text != null) return onText(chatId, friend, message.text)

        when (message.command) {
            "/start", "/menu" -> {
                awaitingHelp.remove(from.id)
                showHome(chatId, friend)
            }
            "/invite" -> if (admin) inviteCommand(chatId, message.argument) else unknown(chatId)
            "/config" -> if (friend.status == Status.APPROVED) sendConfig(chatId, friend) else showHome(chatId, friend)
            "/guide" -> sendGuide(chatId)
            "/help" -> telegram.sendMessage(chatId, Texts.HELP + if (admin) Texts.ADMIN_HELP else "")
            "/pending" -> if (admin) showPending(chatId) else unknown(chatId)
            "/users" -> if (admin) showUsers(chatId) else unknown(chatId)
            "/stats" -> if (admin) showStats(chatId) else unknown(chatId)
            "/revoke" -> if (admin) revokeCommand(chatId, message.argument) else unknown(chatId)
            else -> unknown(chatId)
        }
    }

    private fun unknown(chatId: Long) {
        telegram.sendMessage(chatId, Texts.UNKNOWN, listOf(helpRow(), backRow()))
    }

    // ---------- Приглашения ----------

    /** Новичка пускаем только по ссылке-приглашению; кто уже подавал заявку - свой. */
    private fun allowed(friend: Friend) = friend.status != Status.NEW || friend.invitedAt != null

    private fun inviteCode(): String = store.meta("invite_code") ?: newInviteCode()

    private fun newInviteCode(): String {
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val random = SecureRandom()
        val code = (1..INVITE_LENGTH).map { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
        store.setMeta("invite_code", code)
        return code
    }

    private fun inviteCommand(chatId: Long, argument: String) {
        val code = if (argument == "new") newInviteCode() else inviteCode()
        val link = "https://t.me/$botUsername?start=$code"
        val note = if (argument == "new") "Старая ссылка больше не работает (кто уже пришёл по ней, останутся).\n\n" else ""
        telegram.sendMessage(
            chatId,
            "🔗 <b>Ссылка-приглашение</b>\n<code>$link</code>\n\n$note" +
                "Кто откроет её, сможет отправить заявку. Остальным бот ответит, что он закрытый. " +
                "Новую ссылку (если эта утекла): /invite new",
        )
    }

    // ---------- Переписка с админом ----------

    private fun relayToAdmins(friend: Friend, message: Message) {
        val stats = friend.publicKey?.let { runCatching { awg.stats()[it] }.getOrNull() }
        val header = "🙋 <b>Просит помощи</b>\n${friend.title.escapeHtml()} (id <code>${friend.id}</code>)\n" +
            "Устройство: ${friend.device?.title ?: "не выбрано"}, статус: ${statusTitle(friend.status)}\n" +
            "Последнее подключение: ${lastSeen(friend, stats)?.let { ago(it) } ?: "никогда"}" +
            (friend.username?.let { "\nНаписать напрямую: @$it" } ?: "")
        for (admin in config.adminIds) {
            try {
                telegram.sendMessage(admin, header)
                telegram.copyMessage(admin, friend.id, message.messageId, listOf(listOf(Button("✉️ Ответить", "adm:re:${friend.id}"))))
            } catch (e: Exception) {
                log.warn("Could not relay help request to admin {}: {}", admin, e.message)
            }
        }
        telegram.sendMessage(friend.id, Texts.HELP_SENT, listOf(listOf(Button("✍️ Написать ещё", "help_me")), backRow()))
    }

    private fun relayToFriend(adminChatId: Long, friendId: Long, message: Message) {
        try {
            telegram.sendMessage(friendId, "✉️ <b>Ответ админа:</b>")
            telegram.copyMessage(friendId, adminChatId, message.messageId, listOf(listOf(Button("💬 Ответить", "help_me")), backRow()))
            telegram.sendMessage(adminChatId, Texts.ADMIN_REPLY_SENT, listOf(listOf(Button("✉️ Написать ещё", "adm:re:$friendId"))))
        } catch (e: Exception) {
            log.warn("Could not relay reply to {}: {}", friendId, e.message)
            telegram.sendMessage(adminChatId, "😔 Не доставлено (бот заблокирован?): ${e.message?.escapeHtml()}")
        }
    }

    /** Обычный текст: ответ на вопрос опроса, если он сейчас ждёт текста. */
    private fun onText(chatId: Long, friend: Friend, text: String) {
        when (store.survey(friend.id)?.step) {
            SurveyStep.PREV_VPN_NAME -> {
                store.answerSurvey(friend.id, "prev_vpn_name", text.trim().take(100), SurveyStep.DIFFICULTY, now())
                telegram.sendMessage(chatId, Texts.SURVEY_DIFFICULTY, difficultyKeyboard())
            }
            SurveyStep.COMMENT -> finishSurvey(chatId, friend, text.trim().take(1000), null)
            else -> unknown(chatId)
        }
    }

    /** Скриншоты от админа складываются в папку, из них потом делаются картинки для инструкции. */
    private fun saveScreenshot(chatId: Long, message: Message) {
        val fileId = message.photo?.lastOrNull()?.fileId ?: message.document!!.fileId
        val extension = message.document?.fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() } ?: "jpg"
        val dir = File(config.inboxDir).apply { mkdirs() }
        val stamp = LocalDateTime.now(MOSCOW).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val target = File(dir, "$stamp-${message.messageId}.$extension")
        try {
            target.writeBytes(telegram.download(fileId))
        } catch (e: Exception) {
            log.warn("Could not save screenshot: {}", e.message)
            return telegram.sendMessage(chatId, "😔 Не получилось сохранить: ${e.message?.escapeHtml()}").let {}
        }
        telegram.sendMessage(chatId, "${Texts.SCREENSHOT_SAVED}: <code>${target.name}</code> (всего ${dir.list()?.size ?: 0})")
    }

    // ---------- Кнопки ----------

    private fun onCallback(query: CallbackQuery) {
        val data = query.data ?: return
        val chatId = query.message?.chat?.id ?: return
        val messageId = query.message.messageId
        val friend = store.touch(query.from.id, query.from.fullName, query.from.username)
        telegram.answerCallback(query.id)

        if (data.startsWith("adm:")) {
            if (config.isAdmin(query.from.id)) onAdminButton(query.from.id, chatId, messageId, data)
            return
        }
        if (!config.isAdmin(query.from.id) && !allowed(friend)) return
        val screen = Screen(chatId, messageId)
        when (data) {
            "home" -> {
                awaitingHelp.remove(friend.id)
                showHome(chatId, friend, screen)
            }
            "request" -> requestAccess(chatId, friend, screen)
            "wait" -> whereIsMyConfig(chatId, friend)
            "devices" -> screen.show(Texts.CHOOSE_DEVICE, deviceKeyboard())
            "modes" -> screen.show(Texts.MODES, backKeyboard())
            "trouble" -> screen.show(
                Texts.TROUBLE,
                listOf(listOf(Button("🙋 Написать админу", "help_me")), backRow()),
            )
            "help_me" -> askForHelp(friend, screen)
            "guide" -> sendGuide(chatId)
            "config" -> if (friend.status == Status.APPROVED) sendConfig(chatId, friend) else showHome(chatId, friend)
            "config_all" -> if (friend.status == Status.APPROVED) sendFullConfig(chatId, friend)

            "dev:ios" -> chooseDevice(friend, Device.IOS, screen, Texts.IOS_INSTALL, listOf(
                listOf(Button("✅ Установилось", "installed")),
                listOf(Button("⛔ Пишет «недоступно в вашей стране»", "ios:region:0")),
                helpRow(),
                listOf(Button("« Другое устройство", "devices")),
            ))
            "dev:android" -> chooseDevice(friend, Device.ANDROID, screen, Texts.ANDROID_INSTALL, listOf(
                listOf(Button("✅ Установилось", "installed")),
                listOf(Button("📦 Google Play не работает, пришли файл", "apk")),
                helpRow(),
                listOf(Button("« Другое устройство", "devices")),
            ))
            "dev:windows" -> {
                chooseDevice(friend, Device.WINDOWS, screen, Texts.WINDOWS_INSTALL, emptyList())
                sendApp(chatId, App.WINDOWS_MSI, null, installedKeyboard())
            }
            "dev:mac" -> chooseDevice(friend, Device.MAC, screen, Texts.MAC_INSTALL, listOf(
                listOf(Button("✅ Установилось", "installed")),
                listOf(Button("⛔ Пишет «недоступно в вашей стране»", "ios:region:0")),
                helpRow(),
                listOf(Button("« Другое устройство", "devices")),
            ))
            "apk" -> sendApp(chatId, App.ANDROID_APK, Texts.ANDROID_APK, installedKeyboard())
            "installed" -> installed(chatId, store.get(friend.id)!!, screen)
            "survey" -> if (friend.status == Status.APPROVED) askSurvey(friend.id, Texts.SURVEY_MANUAL, screen) else showHome(chatId, friend, screen)
            else -> when {
                data.startsWith("sv:") -> onSurveyButton(chatId, friend, screen, data)
                data.startsWith("ios:region:") -> showRegionStep(screen, data.substringAfterLast(':').toInt())
                data.startsWith("import:") -> screen.show(importText(Device.valueOf(data.substringAfter(':'))), listOf(helpRow(), backRow()))
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
            add(helpRow())
        }
        screen.show(steps[index], keyboard)
    }

    private fun installed(chatId: Long, friend: Friend, screen: Screen) {
        store.setInstalled(friend.id, now())
        when (friend.status) {
            Status.APPROVED -> {
                screen.show("👍 Отлично, приложение стоит! Теперь добавим конфиг.", emptyList())
                sendConfig(chatId, friend)
            }
            Status.PENDING -> screen.show(Texts.WAIT_AFTER_INSTALL, pendingKeyboard())
            else -> showHome(chatId, friend, screen)
        }
    }

    /** "Нужна помощь": следующее сообщение пользователя (текст, скриншот) уйдёт админу, см. [relayToAdmins]. */
    private fun askForHelp(friend: Friend, screen: Screen) {
        awaitingHelp.add(friend.id)
        screen.show(Texts.HELP_ASK, listOf(listOf(Button("« Отмена", "home"))))
    }

    // ---------- Опрос ----------

    private fun askSurvey(friendId: Long, intro: String, screen: Screen? = null) {
        store.startSurvey(friendId, now())
        (screen ?: Screen(friendId, null)).show(intro, resultKeyboard())
    }

    private fun onSurveyButton(chatId: Long, friend: Friend, screen: Screen, data: String) {
        val (_, question, answer) = data.split(':')
        // кнопки старого, уже пройденного опроса
        if (store.survey(friend.id)?.step == null) return showHome(chatId, friend, screen)
        when (question) {
            "r" -> {
                store.answerSurvey(friend.id, "result", SurveyResult.valueOf(answer).name, SurveyStep.PREV_VPN, now())
                screen.show(Texts.SURVEY_PREV_VPN, listOf(PrevVpn.entries.map { Button(it.title, "sv:p:${it.name}") }))
            }
            "p" -> {
                val prev = PrevVpn.valueOf(answer)
                if (prev == PrevVpn.NONE) {
                    store.answerSurvey(friend.id, "prev_vpn", prev.name, SurveyStep.DIFFICULTY, now())
                    screen.show(Texts.SURVEY_DIFFICULTY, difficultyKeyboard())
                } else {
                    store.answerSurvey(friend.id, "prev_vpn", prev.name, SurveyStep.PREV_VPN_NAME, now())
                    screen.show(
                        "<b>2/4.</b> ${prev.title}. ${Texts.SURVEY_PREV_VPN_NAME}",
                        listOf(listOf(Button("Пропустить »", "sv:pn:skip"))),
                    )
                }
            }
            "pn" -> {
                store.answerSurvey(friend.id, "prev_vpn_name", null, SurveyStep.DIFFICULTY, now())
                screen.show(Texts.SURVEY_DIFFICULTY, difficultyKeyboard())
            }
            "d" -> {
                store.answerSurvey(friend.id, "difficulty", Difficulty.valueOf(answer).name, SurveyStep.COMMENT, now())
                screen.show(Texts.SURVEY_COMMENT, listOf(listOf(Button("Пропустить »", "sv:c:skip"))))
            }
            "c" -> finishSurvey(chatId, friend, null, screen)
        }
    }

    private fun finishSurvey(chatId: Long, friend: Friend, comment: String?, screen: Screen?) {
        store.answerSurvey(friend.id, "comment", comment, null, now())
        val survey = store.survey(friend.id)!!
        val ok = survey.result == SurveyResult.OK
        val keyboard = if (ok) backKeyboard() else listOf(listOf(Button("🆘 Не работает", "trouble")), backRow())
        (screen ?: Screen(chatId, null)).show(if (ok) Texts.SURVEY_THANKS else Texts.SURVEY_THANKS_TROUBLE, keyboard)
        notifyAdmins("🗳 <b>Ответы на опрос</b>\n" + describeSurvey(friend, survey), emptyList())
    }

    private fun describeSurvey(friend: Friend, survey: Survey): String = buildString {
        appendLine("${friend.title.escapeHtml()}, ${friend.device?.title ?: "устройство не выбрано"}")
        appendLine("Итог: ${survey.result?.title ?: "—"}")
        append("VPN раньше: ${survey.prevVpn?.title ?: "—"}")
        survey.prevVpnName?.let { append(" (${it.escapeHtml()})") }
        appendLine()
        appendLine("Инструкция: ${survey.difficulty?.title ?: "—"}")
        survey.comment?.let { append("💬 «${it.escapeHtml()}»") }
    }.trim()

    // ---------- Наблюдение за подключениями ----------

    private fun startMonitor() {
        val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "monitor").apply { isDaemon = true } }
        executor.scheduleWithFixedDelay({
            try {
                monitorTick()
            } catch (e: Exception) {
                log.error("Monitor tick failed", e)
            }
        }, 1, MONITOR_EVERY.toMinutes(), TimeUnit.MINUTES)
    }

    /** Раз в [MONITOR_EVERY]: пишет трафик по дням, поздравляет с первым подключением, зовёт в опрос, шлёт сводку. */
    private fun monitorTick() {
        val stats = awg.stats()
        val day = today()
        for (before in store.list(Status.APPROVED)) {
            val key = before.publicKey ?: continue
            stats[key]?.let { store.recordPeer(before.id, key, it, day) }
            val friend = store.get(before.id) ?: continue
            try {
                if (before.firstSeenAt == null && friend.firstSeenAt != null) {
                    telegram.sendMessage(friend.id, "🎉 Вижу, VPN подключился, всё работает!\n\n" + Texts.KEEP_IT_ON, backKeyboard())
                }
                // before, а не friend: чтобы поздравление и опрос не пришли одновременно
                maybeAskSurvey(before)
            } catch (e: Exception) {
                log.warn("Could not message {}: {}", friend.id, e.message)
            }
        }
        maybeSendDigest()
    }

    private fun maybeAskSurvey(friend: Friend) {
        if (store.survey(friend.id) != null) return
        val firstSeen = friend.firstSeenAt
        val approvedAt = friend.approvedAt ?: friend.requestedAt ?: return
        when {
            firstSeen != null && now() - firstSeen >= SURVEY_AFTER_CONNECT.seconds -> askSurvey(friend.id, Texts.SURVEY_CONNECTED)
            firstSeen == null && now() - approvedAt >= SURVEY_IF_SILENT.seconds -> askSurvey(friend.id, Texts.SURVEY_NOT_CONNECTED)
        }
    }

    /** По понедельникам после 10:00 по Москве админам приходит /stats. */
    private fun maybeSendDigest() {
        val moscow = LocalDateTime.now(MOSCOW)
        if (moscow.dayOfWeek != DayOfWeek.MONDAY || moscow.hour < 10) return
        val week = moscow.toLocalDate().toString()
        if (store.meta("digest_day") == week) return
        store.setMeta("digest_day", week)
        val report = "📅 <b>Сводка за неделю</b>\n\n" + statsReport()
        for (admin in config.adminIds) runCatching { sendLong(admin, report) }
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
            add(helpRow())
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
            telegram.sendMessage(chatId, "😔 Не получилось скачать установщик, попробуйте позже или напишите админу.", listOf(helpRow(), backRow()))
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

    private fun onAdminButton(adminId: Long, chatId: Long, messageId: Long, data: String) {
        val (_, action, idText) = data.split(':')
        if (action == "cancel") {
            replyingTo.remove(adminId)
            return telegram.editMessage(chatId, messageId, "Отменено")
        }
        val friend = store.get(idText.toLong()) ?: return
        if (action == "re") {
            replyingTo[adminId] = friend.id
            telegram.sendMessage(
                chatId,
                "${Texts.ADMIN_REPLY_PROMPT}\nКому: ${friend.title.escapeHtml()}",
                listOf(listOf(Button("« Отмена", "adm:cancel:0"))),
            )
            return
        }
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
        store.approve(friend.id, index, privateKey, publicKey, now())
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
            val seen = lastSeen(friend, peer)?.let { ago(it) } ?: "ни разу"
            val traffic = peer?.let { " · ↓${mb(it.sent)} ↑${mb(it.received)}" } ?: ""
            "• ${friend.title.escapeHtml()}\n   <code>${friend.id}</code> · ${configs.ipv4(friend.ipIndex!!)} · " +
                "${friend.device?.title ?: "?"} · был ${seen}$traffic"
        }
        sendLong(chatId, "👥 <b>Доступ есть у ${friends.size}</b>\n\n" + lines.joinToString("\n"))
    }

    private fun lastSeen(friend: Friend, peer: PeerStats?): Long? =
        maxOf(friend.lastSeenAt ?: 0, peer?.lastHandshake ?: 0).takeIf { it > 0 }

    private fun showStats(chatId: Long) {
        // свежий срез, чтобы не ждать следующего тика
        runCatching { monitorTick() }.onFailure { log.warn("monitor tick before stats failed: {}", it.message) }
        sendLong(chatId, statsReport())
    }

    /**
     * Кто пользуется: был трафик за 7 дней. Забросил: подключался, но 7 дней тишина. Плюс воронка и итоги опроса.
     * "Активный день" - хотя бы [ACTIVE_DAY_BYTES]: включённый без дела VPN за сутки набирает меньше на keepalive.
     */
    private fun statsReport(): String {
        val friends = store.list(Status.APPROVED)
        val funnel = store.funnel()
        val week = store.activeDaysSince(daysAgo(6), ACTIVE_DAY_BYTES)
        val month = store.trafficSince(daysAgo(29))
        val todayActive = store.activeDaysSince(today(), ACTIVE_DAY_BYTES).size
        val (never, connected) = friends.partition { it.firstSeenAt == null }
        val (active, idle) = connected.partition { (week[it.id] ?: 0) > 0 }

        return buildString {
            appendLine("📊 <b>Статистика</b>")
            appendLine()
            appendLine("Воронка: заявок ${funnel.requested} → доступ ${funnel.approved} → «Установилось» ${funnel.installed} → подключились ${funnel.connected}")
            appendLine("Сейчас с доступом: ${friends.size}, сегодня активны: $todayActive, трафик за 30 дней: ${size(month.values.sum())}")
            appendLine()
            appendLine("🟢 <b>Пользуются</b> (трафик за 7 дней): ${active.size}")
            active.sortedByDescending { month[it.id] ?: 0 }.forEach {
                appendLine("• ${it.title.escapeHtml()}: ${week[it.id]} из 7 дн, ${size(month[it.id] ?: 0)} за 30 дн, был ${ago(it.lastSeenAt)}")
            }
            appendLine()
            appendLine("🟡 <b>Забросили</b> (7+ дней тишина): ${idle.size}")
            idle.sortedBy { it.lastSeenAt ?: 0 }.forEach {
                appendLine("• ${it.title.escapeHtml()}: был ${ago(it.lastSeenAt)}, ${size(month[it.id] ?: 0)} за 30 дн")
            }
            appendLine()
            appendLine("⚪ <b>Ни разу не подключались</b>: ${never.size}")
            never.forEach {
                val installed = if (it.installedAt != null) "нажал «Установилось»" else "не нажал «Установилось»"
                appendLine("• ${it.title.escapeHtml()}: доступ ${since(it.approvedAt ?: it.requestedAt)} назад, ${it.device?.title ?: "устройство ?"}, $installed")
            }
            appendLine()
            append(surveyReport())
        }.trim()
    }

    private fun surveyReport(): String {
        val surveys = store.surveys()
        val answered = surveys.filter { it.result != null }
        if (answered.isEmpty()) return "🗳 <b>Опрос</b>: пока никто не ответил (спрошено ${surveys.size})"
        fun <T : Enum<T>> counts(values: List<T?>, titles: Map<T, String>) =
            titles.entries.joinToString(" · ") { (key, title) -> "$title ${values.count { it == key }}" }
        val names = answered.mapNotNull { it.prevVpnName?.trim()?.lowercase() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key.escapeHtml()}" + if (it.value > 1) " ×${it.value}" else "" }
        val byId = surveys.associate { it.friendId to store.get(it.friendId) }
        val comments = answered.filter { it.comment != null }.sortedByDescending { it.doneAt ?: 0 }.take(10)
        return buildString {
            appendLine("🗳 <b>Опрос</b>: ответили ${answered.size} из ${surveys.size} спрошенных")
            appendLine("Итог: " + counts(answered.map { it.result }, SurveyResult.entries.associateWith { it.title }))
            appendLine("VPN раньше: " + counts(answered.map { it.prevVpn }, PrevVpn.entries.associateWith { it.title.lowercase() }))
            if (names.isNotEmpty()) appendLine("Какие: $names")
            appendLine("Инструкция: " + counts(answered.map { it.difficulty }, Difficulty.entries.associateWith { it.title.substringBefore(' ') }))
            if (comments.isNotEmpty()) {
                appendLine()
                appendLine("💬 <b>Отзывы</b>")
                comments.forEach {
                    appendLine("«${it.comment!!.take(300).escapeHtml()}» — ${byId[it.friendId]?.name?.escapeHtml() ?: it.friendId}")
                }
            }
        }
    }

    /** Telegram режет сообщения на 4096 символах: длинный отчёт шлём кусками по строкам. */
    private fun sendLong(chatId: Long, html: String) {
        val chunk = StringBuilder()
        for (line in html.lines()) {
            if (chunk.length + line.length + 1 > MESSAGE_LIMIT) {
                telegram.sendMessage(chatId, chunk.toString())
                chunk.clear()
            }
            chunk.appendLine(line)
        }
        if (chunk.isNotBlank()) telegram.sendMessage(chatId, chunk.toString())
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
        listOf(Button("🆘 Не работает", "trouble"), Button("💬 Оставить отзыв", "survey")),
    )

    private fun resultKeyboard(): Keyboard = SurveyResult.entries.map { listOf(Button(it.title, "sv:r:${it.name}")) }

    private fun difficultyKeyboard(): Keyboard = Difficulty.entries.map { listOf(Button(it.title, "sv:d:${it.name}")) }

    private fun pendingKeyboard(): Keyboard = listOf(
        listOf(Button("📲 Установить приложение", "devices")),
        listOf(Button("⏳ Где мой конфиг?", "wait")),
        helpRow(),
    )

    private fun deviceKeyboard(withBack: Boolean = true): Keyboard = buildList {
        add(listOf(Button("🍏 iPhone / iPad", "dev:ios"), Button("🤖 Android", "dev:android")))
        add(listOf(Button("💻 Windows", "dev:windows"), Button("🍎 Mac", "dev:mac")))
        if (withBack) add(backRow())
    }

    private fun installedKeyboard(): Keyboard = listOf(
        listOf(Button("✅ Установилось", "installed")),
        helpRow(),
        listOf(Button("« Другое устройство", "devices")),
    )

    private fun helpRow() = listOf(Button("🙋 Нужна помощь с настройкой", "help_me"))

    private fun statusTitle(status: Status) = when (status) {
        Status.NEW -> "ещё без заявки"
        Status.PENDING -> "ждёт одобрения"
        Status.APPROVED -> "доступ есть"
        Status.REJECTED -> "отклонён"
        Status.REVOKED -> "доступ отключён"
    }

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
        val MONITOR_EVERY: Duration = Duration.ofMinutes(10)
        /** Через сколько после первого подключения спросить, как дела. */
        val SURVEY_AFTER_CONNECT: Duration = Duration.ofMinutes(30)
        /** Если так и не подключился - через сколько после одобрения спросить, что не так. */
        val SURVEY_IF_SILENT: Duration = Duration.ofHours(24)
        const val ACTIVE_DAY_BYTES = 2L * 1024 * 1024
        const val MESSAGE_LIMIT = 4000
        const val INVITE_LENGTH = 12
        val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

        fun now() = Instant.now().epochSecond

        fun today(): String = LocalDate.now(MOSCOW).toString()
        fun daysAgo(days: Long): String = LocalDate.now(MOSCOW).minusDays(days).toString()

        fun size(bytes: Long) = if (bytes >= 1L shl 30) "%.1f ГБ".format(bytes / 1073741824.0) else mb(bytes)

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
