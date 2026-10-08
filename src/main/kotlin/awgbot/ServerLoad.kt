package awgbot

import java.io.File

/** Нагрузка на сервер из /proc и /sys: читать их может и не root. */
class ServerLoad(private val iface: String) {

    /** Байты через внешний интерфейс (всего, не только VPN): принято, отправлено. */
    fun netBytes(): Pair<Long, Long> = read("rx_bytes") to read("tx_bytes")

    /** Скорость в Мбит/с (вход, выход) за [millis] мс и загрузка процессора в процентах за то же время. */
    fun measure(millis: Long = 1500): Snapshot {
        val (rx0, tx0) = netBytes()
        val cpu0 = cpuTimes()
        Thread.sleep(millis)
        val (rx1, tx1) = netBytes()
        val cpu1 = cpuTimes()
        val busy = (cpu1.first - cpu0.first).toDouble()
        val total = (cpu1.second - cpu0.second).toDouble().coerceAtLeast(1.0)
        return Snapshot(mbit(rx1 - rx0, millis), mbit(tx1 - tx0, millis), 100 * busy / total, memAvailableMb())
    }

    fun memAvailableMb(): Long =
        File("/proc/meminfo").readLines().first { it.startsWith("MemAvailable:") }
            .split(Regex("\\s+"))[1].toLong() / 1024

    private fun read(name: String) = File("/sys/class/net/$iface/statistics/$name").readText().trim().toLong()

    /** (занято, всего) в тиках из первой строки /proc/stat; простой - это idle + iowait. */
    private fun cpuTimes(): Pair<Long, Long> {
        val values = File("/proc/stat").readLines().first().split(Regex("\\s+")).drop(1).map { it.toLong() }
        val idle = values[3] + values.getOrElse(4) { 0 }
        return values.sum() - idle to values.sum()
    }

    data class Snapshot(val inMbit: Double, val outMbit: Double, val cpuPercent: Double, val memAvailableMb: Long)

    companion object {
        fun mbit(bytes: Long, millis: Long) = bytes * 8.0 / 1_000_000 / (millis / 1000.0)
    }
}
