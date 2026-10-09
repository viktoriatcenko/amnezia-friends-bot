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
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit

class FriendsBot(
    private val telegram: TelegramClient,
    private val store: Store,
    private val awg: Awg,
    private val configs: ClientConfigs,
    private val apps: Apps,
    private val config: Config,
    private val load: ServerLoad,
) {
    private val log = LoggerFactory.getLogger(FriendsBot::class.java)

    /** file_id уже отправленных установщиков: имя файла -> id, чтобы не грузить 18 МБ каждый раз. */
    private val uploadedApps = mutableMapOf<String, String>()

    /** Кто нажал "Нужна помощь": следующее сообщение уйдёт админу. Живёт в памяти, после перезапуска - заново. */
    private val awaitingHelp = mutableSetOf<Long>()

    /** Админ нажал "Ответить": его следующее сообщение уйдёт этому пользователю. */
    private val replyingTo = mutableMapOf<Long, Long>()

    /** Пришёл по общей ссылке и подал заявку: следующий текст - кто дал ссылку. */
    private val awaitingReferrer = mutableSetOf<Long>()

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
            val invite = if (message.command == "/start") parseInvite(message.argument) else null
            if (invite != null) {
                store.setInvited(friend.id, now(), invite.by)
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
            "/invite" -> if (admin) telegram.sendMessage(chatId, inviteText()) else unknown(chatId)
            "/admin" -> if (admin) showAdmin(Screen(chatId, null)) else unknown(chatId)
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

    /** Одна постоянная ссылка на всех: код придумывается один раз и хранится в базе. */
    @Synchronized
    private fun inviteCode(): String = store.meta("invite_code") ?: run {
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val random = SecureRandom()
        val code = (1..INVITE_LENGTH).map { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
        store.setMeta("invite_code", code)
        code
    }

    private fun inviteLink() = "https://t.me/$botUsername?start=${inviteCode()}"

    private fun inviteText() = "🔗 <b>Ссылка-приглашение</b>\n<code>${inviteLink()}</code>\n\n" +
        "Ссылка одна и не меняется. Кто откроет её, сможет отправить заявку, а доступ всё равно выдаёте вы. " +
        "Остальным бот ответит, что он закрытый.\n\n" +
        "🤝 У каждого друга с доступом есть своя ссылка (кнопка «Пригласить друга» в меню). " +
        "Кто придёт по ней, у того в заявке будет видно, от кого он."

    /**
     * Личная ссылка друга: f<id>x<подпись>. Подпись - HMAC от id на общем коде, чтобы нельзя было
     * подставить чужой id; заводить отдельные коды в базе не нужно.
     */
    private fun personalCode(friendId: Long): String = "f${friendId}x${signature(friendId)}"

    private fun signature(friendId: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(inviteCode().toByteArray(), "HmacSHA256"))
        return mac.doFinal(friendId.toString().toByteArray()).take(5).joinToString("") { "%02x".format(it) }
    }

    private class Invite(val by: Long?)

    /** Код из /start: общий -> Invite(null), личный рабочий -> Invite(id пригласившего), иначе null. */
    private fun parseInvite(code: String): Invite? {
        if (code == inviteCode()) return Invite(null)
        val match = PERSONAL_CODE.matchEntire(code) ?: return null
        val by = match.groupValues[1].toLongOrNull() ?: return null
        if (match.groupValues[2] != signature(by)) return null
        val referrer = store.get(by) ?: return null
        // приглашать может только тот, у кого есть доступ (или админ)
        return if (referrer.status == Status.APPROVED || config.isAdmin(by)) Invite(by) else null
    }

    private fun showMyInvite(friend: Friend, screen: Screen) {
        val link = if (config.isAdmin(friend.id)) inviteLink() else "https://t.me/$botUsername?start=${personalCode(friend.id)}"
        screen.show(Texts.MY_INVITE.replace("{link}", link), backKeyboard())
    }

    /** Как человек показывается в заявке и карточке: откуда пришёл. */
    private fun referrerText(friend: Friend): String = when {
        friend.invitedBy != null -> "от ${store.get(friend.invitedBy)?.title?.escapeHtml() ?: friend.invitedBy.toString()}"
        friend.referrerNote != null -> "по общей ссылке, говорит, что от «${friend.referrerNote.escapeHtml()}»"
        friend.invitedAt != null -> "по общей ссылке"
        else -> "неизвестно (пришёл до приглашений)"
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
        if (awaitingReferrer.remove(friend.id)) {
            val note = text.trim().take(100)
            store.setReferrerNote(friend.id, note)
            notifyAdmins("🤝 ${friend.title.escapeHtml()} говорит, что ссылку дал(а): «${note.escapeHtml()}»", emptyList())
            telegram.sendMessage(chatId, Texts.REFERRER_THANKS)
            return
        }
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
        if (data.startsWith("ap:")) {
            if (config.isAdmin(query.from.id)) onPanelButton(Screen(chatId, messageId), data)
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
            "invite_me" -> if (friend.status == Status.APPROVED || config.isAdmin(friend.id)) showMyInvite(friend, screen)
            "donate" -> screen.show(Texts.DONATE, listOf(listOf(Button("💛 Открыть сбор", url = Texts.DONATE_URL)), backRow()))
            "ref_skip" -> {
                awaitingReferrer.remove(friend.id)
                screen.show(Texts.REFERRER_THANKS, emptyList())
            }
            "survey" -> if (friend.status == Status.APPROVED) askSurvey(friend.id, Texts.SURVEY_MANUAL, screen) else showHome(chatId, friend, screen)
            else -> when {
                data.startsWith("sv:") -> onSurveyButton(chatId, friend, screen, data)
                data.startsWith("ios:region:") -> showRegionStep(screen, data.substringAfterLast(':').toInt())
                data.startsWith("import:") -> {
                    val device = Device.valueOf(data.substringAfter(':'))
                    val keyboard = listOf(helpRow(), backRow())
                    if (Steps.forImport(device).isEmpty()) screen.show(importText(device), keyboard)
                    else sendImport(chatId, device, keyboard)
                }
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
        val withPanel = if (config.isAdmin(friend.id)) keyboard + listOf(listOf(Button("👑 Админка", "ap:home"))) else keyboard
        (screen ?: Screen(chatId, null)).show(text, withPanel)
    }

    private fun requestAccess(chatId: Long, friend: Friend, screen: Screen) {
        if (friend.status != Status.NEW) return showHome(chatId, friend, screen)
        store.requestAccess(friend.id, now())
        screen.show(Texts.REQUEST_SENT, deviceKeyboard(withBack = false))
        notifyAdmins(
            "🆕 <b>Заявка на доступ</b>\n${friend.title.escapeHtml()}\nid <code>${friend.id}</code>\nПришёл: ${referrerText(friend)}",
            decisionKeyboard(friend.id),
        )
        // по общей ссылке не видно, кто её переслал: спросим самого человека
        if (friend.invitedBy == null && !config.isAdmin(friend.id)) {
            awaitingReferrer.add(friend.id)
            telegram.sendMessage(friend.id, Texts.ASK_REFERRER, listOf(listOf(Button("Пропустить »", "ref_skip"))))
        }
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
    @Synchronized
    private fun monitorTick() {
        val stats = awg.stats()
        val day = today()
        for (before in store.list(Status.APPROVED)) {
            val key = before.publicKey ?: continue
            stats[key]?.let { store.recordPeer(before.id, key, it, day) }
            val friend = store.get(before.id) ?: continue
            try {
                if (before.firstSeenAt == null && friend.firstSeenAt != null) {
                    telegram.sendMessage(friend.id, "🎉 Вижу, VPN подключился, всё работает!\n\n" + Texts.KEEP_IT_ON + Texts.DONATE_HINT, backKeyboard())
                }
                // before, а не friend: чтобы поздравление и опрос не пришли одновременно
                maybeAskSurvey(before)
            } catch (e: Exception) {
                log.warn("Could not message {}: {}", friend.id, e.message)
            }
        }
        recordPeak()
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
        if (device != null) sendImport(chatId, device, keyboard)
        else telegram.sendMessage(chatId, "Куда будете ставить? Покажу, как добавить конфиг 👇", deviceKeyboard() + listOf(keyboard.first()))
    }

    /** "Как добавить конфиг": сначала картинки со стрелками (если есть для устройства), под ними текст. */
    private fun sendImport(chatId: Long, device: Device, keyboard: Keyboard) {
        val images = Steps.forImport(device)
        try {
            when (images.size) {
                0 -> Unit
                1 -> telegram.sendPhoto(chatId, InputFile.Upload(images[0].name, images[0].bytes))
                else -> telegram.sendAlbum(chatId, images.map { InputFile.Upload(it.name, it.bytes) })
            }
        } catch (e: Exception) {
            log.warn("Could not send step images for {}: {}", device, e.message)
        }
        telegram.sendMessage(chatId, importText(device), keyboard)
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
        val usage = UsageView()
        val funnel = store.funnel()
        return buildString {
            appendLine("📊 <b>Статистика</b>")
            appendLine()
            appendLine("Воронка: заявок ${funnel.requested} → доступ ${funnel.approved} → «Установилось» ${funnel.installed} → подключились ${funnel.connected}")
            appendLine("Сейчас с доступом: ${usage.approved.size}, трафик за 30 дней: ${size(usage.month.values.sum())}")
            for (kind in listOf(ListKind.ACTIVE, ListKind.IDLE, ListKind.NEVER)) {
                appendLine()
                appendLine("${kind.title}: ${usage.of(kind).size}")
                usage.of(kind).forEach { appendLine(usage.line(it)) }
            }
            appendLine()
            append(surveyReport())
        }.trim()
    }

    // ---------- Админка (кнопки) ----------

    /** Какие списки пользователей есть в админке. */
    private enum class ListKind(val code: String, val title: String) {
        ACTIVE("active", "🟢 <b>Пользуются</b> (трафик за 7 дней)"),
        IDLE("idle", "🟡 <b>Забросили</b> (подключались, но 7+ дней тишина)"),
        NEVER("never", "⚪ <b>Ни разу не подключались</b>"),
        PENDING("pending", "🕐 <b>Ждут одобрения</b>"),
        ALL("all", "👥 <b>Все с доступом</b>"),
    }

    /**
     * Срез "кто пользуется": активный день - хотя бы [ACTIVE_DAY_BYTES] трафика
     * (включённый без дела VPN за сутки набирает меньше на keepalive).
     */
    private inner class UsageView {
        val approved = store.list(Status.APPROVED)
        val activeDays = store.activeDaysSince(daysAgo(6), ACTIVE_DAY_BYTES)
        val today = store.trafficSince(today())
        val week = store.trafficSince(daysAgo(6))
        val month = store.trafficSince(daysAgo(29))

        fun of(kind: ListKind): List<Friend> = when (kind) {
            ListKind.ACTIVE -> approved.filter { it.firstSeenAt != null && (activeDays[it.id] ?: 0) > 0 }
                .sortedByDescending { week[it.id] ?: 0 }
            ListKind.IDLE -> approved.filter { it.firstSeenAt != null && (activeDays[it.id] ?: 0) == 0 }
                .sortedBy { it.lastSeenAt ?: 0 }
            ListKind.NEVER -> approved.filter { it.firstSeenAt == null }
            ListKind.PENDING -> store.list(Status.PENDING)
            ListKind.ALL -> approved.sortedByDescending { it.lastSeenAt ?: 0 }
        }

        /** Одна строка про пользователя для списков. */
        fun line(friend: Friend): String {
            val name = friend.title.escapeHtml()
            return when {
                friend.status == Status.PENDING -> "• $name: ждёт ${since(friend.requestedAt)}"
                friend.firstSeenAt == null -> {
                    val installed = if (friend.installedAt != null) "нажал «Установилось»" else "не нажал «Установилось»"
                    "• $name: доступ ${since(friend.approvedAt ?: friend.requestedAt)} назад, ${friend.device?.title ?: "устройство ?"}, $installed"
                }
                else -> "• $name: ${activeDays[friend.id] ?: 0} из 7 дн, ${size(week[friend.id] ?: 0)} за неделю, был ${ago(friend.lastSeenAt)}"
            }
        }
    }

    private fun showAdmin(screen: Screen) {
        runCatching { monitorTick() }.onFailure { log.warn("monitor tick before panel failed: {}", it.message) }
        val usage = UsageView()
        val counts = ListKind.entries.associateWith { usage.of(it).size }
        val peak = store.meta("peak:${today()}")?.toDouble() ?: 0.0
        val activeToday = usage.today.count { it.value >= ACTIVE_DAY_BYTES }
        val text = """👑 <b>Админка</b>

👥 С доступом: <b>${usage.approved.size}</b> (мест в подсети ${LAST_IP - FIRST_IP + 1})
🟢 Пользуются: <b>${counts[ListKind.ACTIVE]}</b> · 🟡 Забросили: <b>${counts[ListKind.IDLE]}</b> · ⚪ Не подключались: <b>${counts[ListKind.NEVER]}</b>
🕐 Ждут одобрения: <b>${counts[ListKind.PENDING]}</b>

📶 Сегодня пользовались: $activeToday, трафик за неделю: ${size(usage.week.values.sum())}
🖥 Пик нагрузки сегодня: ${"%.0f".format(peak)} из ${config.channelMbit} Мбит/с

<i>«Пользуются» = был трафик хотя бы в один из последних 7 дней.</i>"""
        fun button(kind: ListKind, label: String) = Button("$label (${counts[kind]})", "ap:l:${kind.code}")
        screen.show(text, listOf(
            listOf(button(ListKind.ACTIVE, "🟢 Пользуются"), button(ListKind.IDLE, "🟡 Забросили")),
            listOf(button(ListKind.NEVER, "⚪ Не подключались"), button(ListKind.PENDING, "🕐 Заявки")),
            listOf(button(ListKind.ALL, "👥 Все"), Button("🗳 Опрос", "ap:survey")),
            listOf(Button("🖥 Сервер", "ap:server"), Button("🔗 Ссылка", "ap:invite")),
            listOf(Button("🤝 Кто кого позвал", "ap:tree"), Button("👀 Как видит новичок", "ap:preview")),
            listOf(Button("🔄 Обновить", "ap:home"), Button("« Меню", "home")),
        ))
    }

    private fun onPanelButton(screen: Screen, data: String) {
        val parts = data.split(':')
        val back = listOf(listOf(Button("« Админка", "ap:home")))
        when (parts[1]) {
            "home" -> showAdmin(screen)
            "l" -> showList(screen, ListKind.entries.first { it.code == parts[2] })
            "u" -> store.get(parts[2].toLong())?.let { showCard(screen, it) }
            "rv" -> store.get(parts[2].toLong())?.let {
                screen.show(
                    "🔒 Отключить доступ для ${it.title.escapeHtml()}?\nКонфиг перестанет работать сразу.",
                    listOf(listOf(Button("Да, отключить", "adm:rv:${it.id}"), Button("« Нет", "ap:u:${it.id}"))),
                )
            }
            "survey" -> screen.show(surveyReport().take(MESSAGE_LIMIT), back)
            "server" -> screen.show(serverReport(), listOf(listOf(Button("🔄 Обновить", "ap:server"))) + back)
            "invite" -> screen.show(inviteText(), back)
            "tree" -> screen.show(inviteTree().take(MESSAGE_LIMIT), back)
            // кнопка "Хочу доступ" тут не настоящая, чтобы админ случайно не сбросил себе статус
            "preview" -> screen.show(
                Texts.WELCOME_NEW,
                listOf(listOf(Button("🔑 Хочу доступ (это превью)", "ap:preview"))) + back,
            )
        }
    }

    /** Дерево приглашений: от админа (общая ссылка) вниз по личным ссылкам. */
    private fun inviteTree(): String {
        val people = store.all().filter { it.status != Status.NEW || it.invitedAt != null }
        val children = people.filter { it.invitedBy != null }.groupBy { it.invitedBy!! }
        val ids = people.map { it.id }.toSet()
        fun mark(friend: Friend) = when (friend.status) {
            Status.APPROVED -> if (friend.firstSeenAt != null) "🟢" else "⚪"
            Status.PENDING -> "🕐"
            else -> "✖️"
        }
        return buildString {
            appendLine("🤝 <b>Кто кого позвал</b>")
            appendLine("🟢 подключался · ⚪ доступ есть, не подключался · 🕐 ждёт · ✖️ отклонён/отключён")
            appendLine()
            fun walk(friend: Friend, depth: Int, seen: Set<Long>) {
                val note = friend.referrerNote?.let { " <i>(говорит: от «${it.escapeHtml()}»)</i>" } ?: ""
                appendLine("${"    ".repeat(depth)}${if (depth > 0) "└ " else ""}${mark(friend)} ${friend.title.escapeHtml()}$note")
                children[friend.id].orEmpty().filter { it.id !in seen }.forEach { walk(it, depth + 1, seen + it.id) }
            }
            // корни: пришли по общей ссылке, до приглашений или от того, кого уже нет в списке
            people.filter { it.invitedBy == null || it.invitedBy !in ids }.forEach { walk(it, 0, setOf(it.id)) }
        }.trim()
    }

    private fun showList(screen: Screen, kind: ListKind) {
        val usage = UsageView()
        val friends = usage.of(kind)
        val text = buildString {
            appendLine("${kind.title}: ${friends.size}")
            appendLine()
            if (friends.isEmpty()) appendLine("Пока никого")
            friends.take(MAX_LIST).forEach { appendLine(usage.line(it)) }
            if (friends.size > MAX_LIST) appendLine("…и ещё ${friends.size - MAX_LIST}")
            if (friends.isNotEmpty()) append("\nНажмите на человека, чтобы открыть карточку 👇")
        }
        val buttons = friends.take(MAX_LIST).map { Button(it.name.take(24), "ap:u:${it.id}") }.chunked(2)
        screen.show(text.take(MESSAGE_LIMIT), buttons + listOf(listOf(Button("« Админка", "ap:home"))))
    }

    private fun showCard(screen: Screen, friend: Friend) {
        val usage = UsageView()
        val peer = friend.publicKey?.let { runCatching { awg.stats()[it] }.getOrNull() }
        val online = peer != null && peer.lastHandshake > 0 && now() - peer.lastHandshake < ONLINE_WINDOW.seconds
        val survey = store.survey(friend.id)
        val text = buildString {
            appendLine("👤 <b>${friend.name.escapeHtml()}</b>" + (friend.username?.let { " @$it" } ?: ""))
            appendLine("id <code>${friend.id}</code> · ${statusTitle(friend.status)} · ${friend.device?.title ?: "устройство не выбрано"}")
            friend.ipIndex?.let { appendLine("Адрес: ${configs.ipv4(it)}") }
            appendLine()
            appendLine("Пришёл: ${referrerText(friend)}")
            val invited = store.all().filter { it.invitedBy == friend.id }
            if (invited.isNotEmpty()) appendLine("Позвал сам (${invited.size}): ${invited.joinToString(", ") { it.name.escapeHtml() }}")
            appendLine("Заявка: ${date(friend.requestedAt)} · доступ: ${date(friend.approvedAt)}")
            appendLine("«Установилось»: ${friend.installedAt?.let { date(it) } ?: "не нажимал"}")
            appendLine("Первое подключение: ${date(friend.firstSeenAt)}")
            appendLine("Последний раз: ${if (online) "🟢 сейчас онлайн" else ago(lastSeen(friend, peer))}")
            appendLine()
            appendLine("Трафик: сегодня ${size(usage.today[friend.id] ?: 0)} · неделя ${size(usage.week[friend.id] ?: 0)} · 30 дней ${size(usage.month[friend.id] ?: 0)}")
            appendLine("Активных дней за неделю: ${usage.activeDays[friend.id] ?: 0} из 7")
            if (survey?.result != null) {
                appendLine()
                appendLine("🗳 Опрос: ${survey.result.title}")
                appendLine("VPN раньше: ${survey.prevVpn?.title ?: "—"}" + (survey.prevVpnName?.let { " (${it.escapeHtml()})" } ?: ""))
                appendLine("Инструкция: ${survey.difficulty?.title ?: "—"}")
                survey.comment?.let { appendLine("💬 «${it.take(500).escapeHtml()}»") }
            }
        }
        val keyboard = buildList {
            add(listOf(Button("✉️ Написать", "adm:re:${friend.id}")))
            when (friend.status) {
                Status.APPROVED -> add(listOf(Button("🔒 Отключить доступ", "ap:rv:${friend.id}")))
                Status.PENDING -> addAll(decisionKeyboard(friend.id))
                else -> Unit
            }
            add(listOf(Button("« Админка", "ap:home")))
        }
        screen.show(text.trim(), keyboard)
    }

    private fun serverReport(): String {
        val now = load.measure()
        val peers = runCatching { awg.stats() }.getOrDefault(emptyMap())
        val online = peers.values.count { it.lastHandshake > 0 && now() - it.lastHandshake < ONLINE_WINDOW.seconds }
        val todayPeak = store.meta("peak:${today()}")?.toDouble() ?: 0.0
        val weekPeak = (0L..6L).maxOf { store.meta("peak:${daysAgo(it)}")?.toDouble() ?: 0.0 }
        val channel = config.channelMbit
        val verdict = when {
            weekPeak < channel * 0.5 -> "✅ Запас большой, можно смело добавлять людей."
            weekPeak < channel * 0.8 -> "🟡 В пики загружено больше половины канала. Пока нормально, но следите."
            else -> "🔴 В пики канал почти забит, у людей может падать скорость. Пора второй сервер или тариф пошире."
        }
        return """🖥 <b>Сервер</b>

Сейчас: ↓ ${"%.1f".format(now.inMbit)} · ↑ ${"%.1f".format(now.outMbit)} Мбит/с (весь сервер, с вашим личным VPN)
Процессор: ${"%.0f".format(now.cpuPercent)}% · свободно памяти: ${now.memAvailableMb} МБ
Друзей онлайн прямо сейчас: $online из ${peers.size}

Пик нагрузки (среднее за 10 мин): сегодня ${"%.0f".format(todayPeak)}, за неделю ${"%.0f".format(weekPeak)} из $channel Мбит/с
$verdict

<i>Канал ≈$channel Мбит/с в каждую сторону. Видео 1080p ≈ 5–8 Мбит/с, 4K ≈ 20. Обычно одновременно смотрит 10–20% людей, так что комфортно ≈ 60–80 активных друзей.</i>"""
    }

    /** Пиковая (за интервал тика) скорость за день: max(вход, выход), сохраняется в meta "peak:<день>". */
    private var lastNet: Triple<Long, Long, Long>? = null

    private fun recordPeak() {
        val (rx, tx) = runCatching { load.netBytes() }.getOrNull() ?: return
        val millis = System.currentTimeMillis()
        val previous = lastNet
        lastNet = Triple(rx, tx, millis)
        if (previous == null || rx < previous.first || tx < previous.second) return
        val elapsed = millis - previous.third
        if (elapsed < 60_000) return // слишком короткий интервал (ручной /stats) - не показателен
        val rate = maxOf(ServerLoad.mbit(rx - previous.first, elapsed), ServerLoad.mbit(tx - previous.second, elapsed))
        val key = "peak:${today()}"
        if (rate > (store.meta(key)?.toDouble() ?: 0.0)) store.setMeta(key, "%.2f".format(Locale.ROOT, rate))
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
        listOf(Button("🤝 Пригласить друга", "invite_me"), Button("💛 Поддержать сервер", "donate")),
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
        const val MAX_LIST = 40
        val PERSONAL_CODE = Regex("f(\\d+)x([0-9a-f]{10})")
        /** Рукопожатие не старше этого - человек сейчас онлайн (keepalive каждые 25 с, рукопожатие раз в 2 мин). */
        val ONLINE_WINDOW: Duration = Duration.ofMinutes(3)

        fun date(epochSeconds: Long?): String = epochSeconds?.let {
            DateTimeFormatter.ofPattern("dd.MM HH:mm").format(Instant.ofEpochSecond(it).atZone(MOSCOW))
        } ?: "—"
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
