package ly.mens.rndpkmn

import java.io.File
import java.io.RandomAccessFile

// Setzt den Titel im "Banner" der ROM (das ist der Name, den Emulator und DS-Menü anzeigen).
// Das Spiel selbst liest den Banner nicht. Er enthält sechs Sprachen à 256 Byte (UTF-16),
// dazu eine Prüfsumme (CRC16), die wir neu berechnen.
object BannerPatch {

	class Result(val ok: Boolean, val text: String)

	private const val BANNER_SIZE = 0x840
	private const val TITLES_AT = 0x240
	private const val TITLE_SIZE = 0x100
	private const val LANGUAGES = 6

	private fun crc16(buf: ByteArray, from: Int, to: Int): Int {
		var crc = 0xFFFF
		for (i in from until to) {
			crc = crc xor (buf[i].toInt() and 0xFF)
			repeat(8) {
				crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
			}
		}
		return crc and 0xFFFF
	}

	fun apply(file: File, line1: String, line2: String): Result {
		// Nur Nintendo-DS-Spiele haben einen Banner (GameBoy und GBA nicht)
		if (!file.name.endsWith(".nds", ignoreCase = true)) return Result(true, "")
		return try {
			RandomAccessFile(file, "rw").use { raf ->
				raf.seek(0x68)
				val o = ByteArray(4)
				raf.readFully(o)
				val bannerOffset = (o[0].toInt() and 0xFF) or
					((o[1].toInt() and 0xFF) shl 8) or
					((o[2].toInt() and 0xFF) shl 16) or
					((o[3].toInt() and 0xFF) shl 24)
				if (bannerOffset <= 0 || bannerOffset + BANNER_SIZE > raf.length()) {
					return Result(false, "Titel nicht geändert: Banner nicht gefunden.")
				}
				val banner = ByteArray(BANNER_SIZE)
				raf.seek(bannerOffset.toLong())
				raf.readFully(banner)
				if (banner[0].toInt() != 1) {
					return Result(false, "Titel nicht geändert: unbekanntes Banner-Format.")
				}
				// Höchstens 127 Zeichen, sonst passt es nicht in das Feld
				val text = (line1 + "\n" + line2).take(127).toByteArray(Charsets.UTF_16LE)
				for (lang in 0 until LANGUAGES) {
					val start = TITLES_AT + lang * TITLE_SIZE
					for (i in 0 until TITLE_SIZE) banner[start + i] = 0
					System.arraycopy(text, 0, banner, start, text.size)
				}
				val crc = crc16(banner, 0x20, BANNER_SIZE)
				banner[2] = (crc and 0xFF).toByte()
				banner[3] = ((crc ushr 8) and 0xFF).toByte()
				raf.seek(bannerOffset.toLong())
				raf.write(banner)
			}
			Result(true, "Titel in der ROM gesetzt.")
		} catch (e: Exception) {
			Result(false, "Titel nicht geändert: ${e.message}")
		}
	}
}
