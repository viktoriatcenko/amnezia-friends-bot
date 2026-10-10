package awgbot

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** Инструкция одним HTML-файлом: те же тексты, что в боте, чтобы не расходились. */
object Guide {
    fun html(): String {
        val sections = listOf(
            "Два режима" to Texts.MODES,
            "VPN можно не выключать" to Texts.KEEP_IT_ON,
            "iPhone / iPad: установка" to Texts.IOS_INSTALL,
            "iPhone: если приложение недоступно в вашей стране" to Texts.IOS_REGION_STEPS.joinToString("\n\n"),
            "iPhone: добавить конфиг" to Texts.IOS_IMPORT,
            "Android: установка" to Texts.ANDROID_INSTALL + "\n\n" + Texts.ANDROID_APK,
            "Android: добавить конфиг" to Texts.ANDROID_IMPORT,
            "Windows" to Texts.WINDOWS_INSTALL + "\n\n" + Texts.WINDOWS_IMPORT,
            "Mac" to Texts.MAC_INSTALL + "\n\n" + Texts.MAC_IMPORT,
            "Если что-то не работает" to Texts.TROUBLE,
            "Приложение ругается на VPN" to Texts.APP_EXCLUDE,
        )
        val toc = sections.mapIndexed { i, (title, _) -> """<li><a href="#s$i">$title</a></li>""" }.joinToString("\n")
        val pictures = mapOf("iPhone: добавить конфиг" to Steps.forImport(Device.IOS))
        val body = sections.mapIndexed { i, (title, text) ->
            val images = pictures[title].orEmpty().joinToString("") {
                """<img src="data:image/jpeg;base64,${Base64.getEncoder().encodeToString(it.bytes)}" alt="">"""
            }
            val gallery = if (images.isEmpty()) "" else """<div class="steps">$images</div>"""
            """<section id="s$i"><h2>$title</h2>$gallery<p>${text.trim().replace("\n", "<br>\n")}</p></section>"""
        }.joinToString("\n")
        return """<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>VPN для своих: инструкция</title>
<style>
  :root { --bg: #fafaf7; --fg: #1d1d1b; --muted: #6b6b66; --card: #ffffff; --line: #e4e2dc; --accent: #2f6fed; }
  @media (prefers-color-scheme: dark) {
    :root { --bg: #151515; --fg: #ececea; --muted: #a3a3a0; --card: #1f1f1f; --line: #333; --accent: #7aa5ff; }
  }
  body { margin: 0; background: var(--bg); color: var(--fg); font: 16px/1.55 -apple-system, "Segoe UI", Roboto, sans-serif; }
  main { max-width: 720px; margin: 0 auto; padding: 24px 16px 64px; }
  h1 { font-size: 26px; margin: 8px 0 4px; }
  h2 { font-size: 20px; margin: 0 0 10px; }
  .lead { color: var(--muted); margin: 0 0 20px; }
  nav, section { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 16px 18px; margin: 14px 0; }
  nav ol { margin: 0; padding-left: 20px; }
  a { color: var(--accent); }
  .steps { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; margin: 0 0 12px; }
  .steps img { width: 100%; border-radius: 10px; border: 1px solid var(--line); }
  code { background: var(--line); border-radius: 5px; padding: 1px 5px; font-size: 14px; word-break: break-all; }
</style>
</head>
<body>
<main>
<h1>🛡 VPN для своих</h1>
<p class="lead">AmneziaWG: установка, настройка и что делать, если не работает. Конфиг выдаёт бот в Telegram.</p>
<nav><ol>
$toc
</ol></nav>
$body
</main>
</body>
</html>
"""
    }
}

object Qr {
    /** PNG с QR-кодом; белые поля по краям нужны, чтобы камера уверенно его ловила. */
    fun png(text: String, size: Int = 720): ByteArray {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2),
        )
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
            }
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
