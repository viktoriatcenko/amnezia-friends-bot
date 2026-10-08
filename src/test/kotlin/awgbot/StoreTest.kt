package awgbot

import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoreTest {
    private val file = File.createTempFile("awgbot", ".db")

    @AfterTest
    fun cleanup() {
        file.delete()
    }

    @Test
    fun `traffic is counted as deltas and survives counter reset`() {
        val store = Store(file.path)
        store.touch(1, "Вася", null)
        store.recordPeer(1, "pub", PeerStats(lastHandshake = 1000, received = 100, sent = 1000), "2026-10-08")
        store.recordPeer(1, "pub", PeerStats(lastHandshake = 2000, received = 150, sent = 1500), "2026-10-08")
        // awg1 перезапустили: счетчики начались заново
        store.recordPeer(1, "pub", PeerStats(lastHandshake = 3000, received = 10, sent = 20), "2026-10-09")

        assertEquals(mapOf(1L to 1500L + 150L + 30L), store.trafficSince("2026-10-01"))
        assertEquals(mapOf(1L to 30L), store.trafficSince("2026-10-09"))
        assertEquals(mapOf(1L to 1), store.activeDaysSince("2026-10-01", minBytes = 1000))
        val friend = store.get(1)!!
        assertEquals(1000L, friend.firstSeenAt)
        assertEquals(3000L, friend.lastSeenAt)
    }

    @Test
    fun `survey goes step by step and restarts clean`() {
        val store = Store(file.path)
        store.touch(1, "Вася", null)
        store.startSurvey(1, now = 10)
        store.answerSurvey(1, "result", SurveyResult.OK.name, SurveyStep.PREV_VPN, now = 11)
        store.answerSurvey(1, "prev_vpn", PrevVpn.PAID.name, SurveyStep.PREV_VPN_NAME, now = 12)
        store.answerSurvey(1, "prev_vpn_name", "Outline", SurveyStep.DIFFICULTY, now = 13)
        store.answerSurvey(1, "difficulty", Difficulty.EASY.name, SurveyStep.COMMENT, now = 14)
        store.answerSurvey(1, "comment", "всё супер", null, now = 15)

        val done = store.survey(1)!!
        assertNull(done.step)
        assertEquals(15L, done.doneAt)
        assertEquals("Outline", done.prevVpnName)

        store.startSurvey(1, now = 20)
        val again = store.survey(1)!!
        assertEquals(SurveyStep.RESULT, again.step)
        assertNull(again.result)
        assertNull(again.doneAt)
    }

    @Test
    fun `old database gets new columns`() {
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use {
            it.createStatement().executeUpdate(
                "create table friends (id integer primary key, name text not null, username text, status text not null, " +
                    "device text, ip_index integer unique, private_key text, public_key text, requested_at integer, last_reminder_at integer)"
            )
            it.createStatement().executeUpdate("insert into friends (id, name, status) values (7, 'Старый', 'APPROVED')")
        }
        val store = Store(file.path)
        store.setInstalled(7, now = 5)
        store.setInvited(7, now = 6)
        assertEquals(5L, store.get(7)!!.installedAt)
        assertEquals(Funnel(requested = 1, approved = 1, installed = 1, connected = 0), store.funnel())
    }
}
