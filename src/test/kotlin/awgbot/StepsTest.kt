package awgbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepsTest {
    @Test
    fun `ios import pictures are bundled in order`() {
        assertEquals(listOf("ios-import-1.jpg", "ios-import-2.jpg", "ios-import-3.jpg", "ios-import-4.jpg"), Steps.forImport(Device.IOS).map { it.name })
        assertEquals(listOf("android-import-1.jpg", "android-import-2.jpg", "android-import-3.jpg"), Steps.forImport(Device.ANDROID).map { it.name })
        assertTrue(Steps.forImport(Device.WINDOWS).isEmpty())
    }

    @Test
    fun `guide embeds pictures`() {
        assertEquals(7, Regex("data:image/jpeg;base64").findAll(Guide.html()).count())
    }
}
