package awgbot

/**
 * Картинки "куда жать" для шагов: resources/steps/<набор>-1.jpg, -2.jpg, ... Делаются из скриншотов
 * скриптом tools/annotate.py. Набора нет - бот показывает только текст.
 */
object Steps {
    class Image(val name: String, val bytes: ByteArray)

    private val cache = mutableMapOf<String, List<Image>>()

    @Synchronized
    fun images(set: String): List<Image> = cache.getOrPut(set) {
        generateSequence(1) { it + 1 }
            .map { "$set-$it.jpg" }
            .map { name -> Steps::class.java.getResourceAsStream("/steps/$name")?.use { Image(name, it.readBytes()) } }
            .takeWhile { it != null }
            .filterNotNull()
            .toList()
    }

    /** Картинки к инструкции "как добавить конфиг" для устройства. */
    fun forImport(device: Device): List<Image> = images("${device.name.lowercase()}-import")
}
