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
    fun approve(id: Long, ipIndex: Int, privateKey: String, publicKey: String) =
        update(id, "status = 'APPROVED', ip_index = ?, private_key = ?, public_key = ?", ipIndex, privateKey, publicKey)

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
    )
}
