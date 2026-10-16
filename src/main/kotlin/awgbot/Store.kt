package awgbot

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

enum class Status {
    /** Написал боту, но доступ еще не просил. */
    NEW,
    PENDING,
    APPROVED,
    REJECTED,
    /** Был доступ, админ отключил. */
    REVOKED,
}

/** Устройство, под которое показывать инструкции. */
enum class Device(val title: String) {
    IOS("iPhone / iPad"),
    ANDROID("Android"),
    WINDOWS("Windows"),
    MAC("Mac"),
}

data class Friend(
    val id: Long,
    val name: String,
    val username: String?,
    val status: Status,
    val device: Device?,
    /** Номер адреса в подсети awg1 (2..254); null, пока конфига нет. */
    val ipIndex: Int?,
    val privateKey: String?,
    val publicKey: String?,
    val requestedAt: Long?,
    val lastReminderAt: Long?,
    val approvedAt: Long? = null,
    /** Нажал "Установилось". */
    val installedAt: Long? = null,
    /** Первое и последнее рукопожатие, которые видел бот (счетчики awg сбрасываются, эти - нет). */
    val firstSeenAt: Long? = null,
    val lastSeenAt: Long? = null,
    /** Пришёл по ссылке-приглашению; без неё бот новичков не пускает. */
    val invitedAt: Long? = null,
    /** Чья личная ссылка привела; null - общая ссылка админа. */
    val invitedBy: Long? = null,
    /** Пришёл по общей ссылке и сам написал, кто её дал. */
    val referrerNote: String? = null,
) {
    /** Как пользователь показывается админу. */
    val title: String
        get() = name + (username?.let { " (@$it)" } ?: "")
}

class Store(path: String) {
    private val connection: Connection

    init {
        File(path).absoluteFile.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:$path")
        connection.createStatement().use {
            it.executeUpdate(
                """
                create table if not exists friends (
                    id integer primary key,
                    name text not null,
                    username text,
                    status text not null,
                    device text,
                    ip_index integer unique,
                    private_key text,
                    public_key text,
                    requested_at integer,
                    last_reminder_at integer
                )
                """.trimIndent()
            )
            // колонки, появившиеся позже: в старой базе их нет
            val existing = it.executeQuery("pragma table_info(friends)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }.toSet()
            }
            for (column in listOf("approved_at", "installed_at", "first_seen_at", "last_seen_at", "invited_at", "invited_by")) {
                if (column !in existing) it.executeUpdate("alter table friends add column $column integer")
            }
            if ("referrer_note" !in existing) it.executeUpdate("alter table friends add column referrer_note text")
            // трафик по дням (по Москве), чтобы видеть, кто пользуется, а кто нет
            it.executeUpdate(
                """
                create table if not exists usage (
                    friend_id integer not null,
                    day text not null,
                    down integer not null,
                    up integer not null,
                    primary key (friend_id, day)
                )
                """.trimIndent()
            )
            // с каких IP подключался конфиг: returns - сколько раз вернулся на этот IP с другого,
            // пока старый был жив. Частые возвраты = конфиг одновременно на нескольких устройствах.
            it.executeUpdate(
                """
                create table if not exists endpoints (
                    friend_id integer not null,
                    day text not null,
                    ip text not null,
                    first_at integer not null,
                    last_at integer not null,
                    returns integer not null default 0,
                    primary key (friend_id, day, ip)
                )
                """.trimIndent()
            )
            // последние увиденные счетчики awg по ключу, чтобы считать приращение
            it.executeUpdate(
                "create table if not exists counters (public_key text primary key, down integer not null, up integer not null)"
            )
            it.executeUpdate(
                """
                create table if not exists survey (
                    friend_id integer primary key,
                    step text,
                    result text,
                    prev_vpn text,
                    prev_vpn_name text,
                    difficulty text,
                    comment text,
                    asked_at integer not null,
                    done_at integer
                )
                """.trimIndent()
            )
            it.executeUpdate("create table if not exists meta (key text primary key, value text not null)")
        }
    }

    /** Заводит пользователя при первом сообщении и обновляет имя, если он его сменил. */
    @Synchronized
    fun touch(id: Long, name: String, username: String?): Friend {
        connection.prepareStatement(
            """
            insert into friends (id, name, username, status) values (?, ?, ?, 'NEW')
            on conflict(id) do update set name = excluded.name, username = excluded.username
            """.trimIndent()
        ).use {
            it.setLong(1, id)
            it.setString(2, name)
            it.setString(3, username)
            it.executeUpdate()
        }
        return get(id)!!
    }

    @Synchronized
    fun get(id: Long): Friend? =
        connection.prepareStatement("select * from friends where id = ?").use {
            it.setLong(1, id)
            it.executeQuery().use { rs -> if (rs.next()) rs.toFriend() else null }
        }

    @Synchronized
    fun list(vararg statuses: Status): List<Friend> =
        connection.prepareStatement(
            "select * from friends where status in (${statuses.joinToString { "?" }}) order by requested_at"
        ).use {
            statuses.forEachIndexed { i, s -> it.setString(i + 1, s.name) }
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toFriend() else null }.toList() }
        }

    @Synchronized
    fun requestAccess(id: Long, now: Long) = update(id, "status = 'PENDING', requested_at = ?, last_reminder_at = ?", now, now)

    @Synchronized
    fun setStatus(id: Long, status: Status) = update(id, "status = ?", status.name)

    @Synchronized
    fun setDevice(id: Long, device: Device) = update(id, "device = ?", device.name)

    @Synchronized
    fun setLastReminder(id: Long, now: Long) = update(id, "last_reminder_at = ?", now)

    @Synchronized
    fun approve(id: Long, ipIndex: Int, privateKey: String, publicKey: String, now: Long) =
        update(
            id,
            "status = 'APPROVED', ip_index = ?, private_key = ?, public_key = ?, approved_at = ?",
            ipIndex, privateKey, publicKey, now,
        )

    @Synchronized
    fun setInvited(id: Long, now: Long, by: Long?) =
        update(id, "invited_at = coalesce(invited_at, ?), invited_by = coalesce(invited_by, ?)", now, by)

    @Synchronized
    fun setReferrerNote(id: Long, note: String) = update(id, "referrer_note = ?", note)

    /** Все, кто когда-либо писал боту (для дерева приглашений). */
    @Synchronized
    fun all(): List<Friend> = list(*Status.entries.toTypedArray())

    @Synchronized
    fun setInstalled(id: Long, now: Long) = update(id, "installed_at = coalesce(installed_at, ?)", now)

    /** Воронка по всем, кто когда-либо писал боту: заявка -> доступ -> "Установилось" -> подключился. */
    @Synchronized
    fun funnel(): Funnel =
        connection.createStatement().use { st ->
            st.executeQuery(
                """
                select count(*) filter (where status != 'NEW'),
                       count(*) filter (where approved_at is not null or status = 'APPROVED'),
                       count(*) filter (where installed_at is not null),
                       count(*) filter (where first_seen_at is not null)
                from friends
                """.trimIndent()
            ).use { rs ->
                rs.next()
                Funnel(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4))
            }
        }

    // ---------- Использование ----------

    /**
     * Запоминает свежие счетчики пира: приращение трафика пишется в день [day], рукопожатие - в first/last_seen.
     * Если счетчики уменьшились (перезапуск awg1), считаем приращение от нуля.
     */
    @Synchronized
    fun recordPeer(friendId: Long, publicKey: String, stats: PeerStats, day: String) {
        val previous = connection.prepareStatement("select down, up from counters where public_key = ?").use {
            it.setString(1, publicKey)
            it.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getLong(2) else null }
        }
        fun delta(current: Long, before: Long?) = if (before == null || current < before) current else current - before
        val down = delta(stats.sent, previous?.first)
        val up = delta(stats.received, previous?.second)
        connection.prepareStatement(
            """
            insert into counters (public_key, down, up) values (?, ?, ?)
            on conflict(public_key) do update set down = excluded.down, up = excluded.up
            """.trimIndent()
        ).use {
            it.setString(1, publicKey)
            it.setLong(2, stats.sent)
            it.setLong(3, stats.received)
            it.executeUpdate()
        }
        if (down + up > 0) {
            connection.prepareStatement(
                """
                insert into usage (friend_id, day, down, up) values (?, ?, ?, ?)
                on conflict(friend_id, day) do update set down = down + excluded.down, up = up + excluded.up
                """.trimIndent()
            ).use {
                it.setLong(1, friendId)
                it.setString(2, day)
                it.setLong(3, down)
                it.setLong(4, up)
                it.executeUpdate()
            }
        }
        if (stats.lastHandshake > 0) {
            update(
                friendId,
                "first_seen_at = coalesce(first_seen_at, ?), last_seen_at = max(coalesce(last_seen_at, 0), ?)",
                stats.lastHandshake, stats.lastHandshake,
            )
        }
    }

    /** Отмечает, что конфиг [friendId] сейчас подключен с [ip]; [returned] - вернулся на него с другого адреса. */
    @Synchronized
    fun recordEndpoint(friendId: Long, day: String, ip: String, at: Long, returned: Boolean) {
        connection.prepareStatement(
            """
            insert into endpoints (friend_id, day, ip, first_at, last_at, returns) values (?, ?, ?, ?, ?, ?)
            on conflict(friend_id, day, ip) do update set last_at = excluded.last_at, returns = returns + excluded.returns
            """.trimIndent()
        ).use {
            it.setLong(1, friendId)
            it.setString(2, day)
            it.setString(3, ip)
            it.setLong(4, at)
            it.setLong(5, at)
            it.setInt(6, if (returned) 1 else 0)
            it.executeUpdate()
        }
    }

    /** По каждому пользователю с дня [fromDay]: (разных IP, возвратов, дней с возвратами). */
    @Synchronized
    fun endpointsSince(fromDay: String): Map<Long, EndpointSummary> =
        connection.prepareStatement(
            """
            select friend_id, count(distinct ip), sum(returns), count(distinct case when returns > 0 then day end)
            from endpoints where day >= ? group by friend_id
            """.trimIndent()
        ).use {
            it.setString(1, fromDay)
            it.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getLong(1), EndpointSummary(rs.getInt(2), rs.getInt(3), rs.getInt(4))) }
            }
        }

    /** Трафик (вниз + вверх) по каждому пользователю с дня [fromDay] включительно. */
    @Synchronized
    fun trafficSince(fromDay: String): Map<Long, Long> =
        connection.prepareStatement("select friend_id, sum(down + up) from usage where day >= ? group by friend_id").use {
            it.setString(1, fromDay)
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong(1) to rs.getLong(2) else null }.toMap() }
        }

    /** В сколько разных дней с [fromDay] у пользователя набралось хотя бы [minBytes] трафика. */
    @Synchronized
    fun activeDaysSince(fromDay: String, minBytes: Long): Map<Long, Int> =
        connection.prepareStatement(
            "select friend_id, count(*) from usage where day >= ? and down + up >= ? group by friend_id"
        ).use {
            it.setString(1, fromDay)
            it.setLong(2, minBytes)
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong(1) to rs.getInt(2) else null }.toMap() }
        }

    // ---------- Опрос ----------

    /** Начинает опрос заново: старые ответы стираются. */
    @Synchronized
    fun startSurvey(friendId: Long, now: Long) {
        connection.prepareStatement(
            """
            insert into survey (friend_id, step, asked_at) values (?, ?, ?)
            on conflict(friend_id) do update set step = excluded.step, result = null, prev_vpn = null,
                prev_vpn_name = null, difficulty = null, comment = null, asked_at = excluded.asked_at, done_at = null
            """.trimIndent()
        ).use {
            it.setLong(1, friendId)
            it.setString(2, SurveyStep.RESULT.name)
            it.setLong(3, now)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun survey(friendId: Long): Survey? =
        connection.prepareStatement("select * from survey where friend_id = ?").use {
            it.setLong(1, friendId)
            it.executeQuery().use { rs -> if (rs.next()) rs.toSurvey() else null }
        }

    @Synchronized
    fun surveys(): List<Survey> =
        connection.createStatement().use { st ->
            st.executeQuery("select * from survey order by asked_at").use { rs ->
                generateSequence { if (rs.next()) rs.toSurvey() else null }.toList()
            }
        }

    /** Записывает ответ в [column] и переводит опрос на шаг [next]; null - опрос закончен. */
    @Synchronized
    fun answerSurvey(friendId: Long, column: String, value: String?, next: SurveyStep?, now: Long) {
        require(column in SURVEY_COLUMNS) { "unknown survey column $column" }
        connection.prepareStatement("update survey set $column = ?, step = ?, done_at = ? where friend_id = ?").use {
            it.setString(1, value)
            it.setString(2, next?.name)
            it.setObject(3, if (next == null) now else null)
            it.setLong(4, friendId)
            it.executeUpdate()
        }
    }

    // ---------- Разное ----------

    @Synchronized
    fun meta(key: String): String? =
        connection.prepareStatement("select value from meta where key = ?").use {
            it.setString(1, key)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    @Synchronized
    fun setMeta(key: String, value: String) {
        connection.prepareStatement(
            "insert into meta (key, value) values (?, ?) on conflict(key) do update set value = excluded.value"
        ).use {
            it.setString(1, key)
            it.setString(2, value)
            it.executeUpdate()
        }
    }

    /** Отзывает доступ и освобождает адрес, ключи забываем - при повторном одобрении будут новые. */
    @Synchronized
    fun revoke(id: Long) =
        update(id, "status = 'REVOKED', ip_index = null, private_key = null, public_key = null")

    @Synchronized
    fun usedIpIndexes(): Set<Int> =
        connection.createStatement().use { st ->
            st.executeQuery("select ip_index from friends where ip_index is not null").use { rs ->
                generateSequence { if (rs.next()) rs.getInt(1) else null }.toSet()
            }
        }

    private fun update(id: Long, set: String, vararg values: Any?) {
        connection.prepareStatement("update friends set $set where id = ?").use {
            values.forEachIndexed { i, v -> it.setObject(i + 1, v) }
            it.setLong(values.size + 1, id)
            it.executeUpdate()
        }
    }

    private fun ResultSet.toFriend() = Friend(
        id = getLong("id"),
        name = getString("name"),
        username = getString("username"),
        status = Status.valueOf(getString("status")),
        device = getString("device")?.let(Device::valueOf),
        ipIndex = getInt("ip_index").takeUnless { wasNull() },
        privateKey = getString("private_key"),
        publicKey = getString("public_key"),
        requestedAt = getLong("requested_at").takeUnless { wasNull() },
        lastReminderAt = getLong("last_reminder_at").takeUnless { wasNull() },
        approvedAt = getLong("approved_at").takeUnless { wasNull() },
        installedAt = getLong("installed_at").takeUnless { wasNull() },
        firstSeenAt = getLong("first_seen_at").takeUnless { wasNull() },
        lastSeenAt = getLong("last_seen_at").takeUnless { wasNull() },
        invitedAt = getLong("invited_at").takeUnless { wasNull() },
        invitedBy = getLong("invited_by").takeUnless { wasNull() },
        referrerNote = getString("referrer_note"),
    )

    private fun ResultSet.toSurvey() = Survey(
        friendId = getLong("friend_id"),
        step = getString("step")?.let(SurveyStep::valueOf),
        result = getString("result")?.let(SurveyResult::valueOf),
        prevVpn = getString("prev_vpn")?.let(PrevVpn::valueOf),
        prevVpnName = getString("prev_vpn_name"),
        difficulty = getString("difficulty")?.let(Difficulty::valueOf),
        comment = getString("comment"),
        askedAt = getLong("asked_at"),
        doneAt = getLong("done_at").takeUnless { wasNull() },
    )

    private companion object {
        val SURVEY_COLUMNS = setOf("result", "prev_vpn", "prev_vpn_name", "difficulty", "comment")
    }
}

data class Funnel(val requested: Int, val approved: Int, val installed: Int, val connected: Int)

/** С каких адресов подключался конфиг: [returns] - возвраты на недавний IP, признак нескольких устройств. */
data class EndpointSummary(val ips: Int, val returns: Int, val daysWithReturns: Int) {
    val shared: Boolean get() = returns >= SHARED_RETURNS

    companion object {
        /** Телефон, гуляющий между Wi-Fi и мобильной сетью, дает единицы возвратов, два устройства - десятки. */
        const val SHARED_RETURNS = 6
    }
}
