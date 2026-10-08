package awgbot

/** Опрос после подключения: получилось ли, был ли VPN раньше, как инструкция, свободный отзыв. */
data class Survey(
    val friendId: Long,
    /** Какой вопрос ждёт ответа; null - опрос пройден (или брошен на середине, если [doneAt] тоже null). */
    val step: SurveyStep?,
    val result: SurveyResult?,
    val prevVpn: PrevVpn?,
    val prevVpnName: String?,
    val difficulty: Difficulty?,
    val comment: String?,
    val askedAt: Long,
    val doneAt: Long?,
)

enum class SurveyStep {
    RESULT,
    PREV_VPN,
    /** Ждём текстом название прежнего VPN. */
    PREV_VPN_NAME,
    DIFFICULTY,
    /** Ждём текстом отзыв. */
    COMMENT,
}

enum class SurveyResult(val title: String) {
    OK("✅ Всё работает"),
    PARTIAL("⚠️ Работает, но не всё"),
    FAIL("❌ Не получилось"),
}

enum class PrevVpn(val title: String) {
    NONE("Не было"),
    FREE("Бесплатный"),
    PAID("Платный"),
}

enum class Difficulty(val title: String) {
    EASY("😊 Легко"),
    NORMAL("😐 Нормально, но пришлось подумать"),
    HARD("😣 Сложно"),
}
