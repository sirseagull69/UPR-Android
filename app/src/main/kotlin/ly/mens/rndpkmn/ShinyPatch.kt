package ly.mens.rndpkmn

import com.dabomstew.pkrandom.newnds.NDSRom
import java.io.File

// Ändert die Shiny-Rate in Pokémon-DS-Spielen (Gen 4 und 5).
// Idee und HGSS-Offsets stammen vom Tool "hgss-shiny-modifier" (choogiesaur, MIT-Lizenz).
// Im entpackten ARM9-Code steht ein einzelnes Byte, das die Shiny-Chance bestimmt
// (Original: 8 von 65536). Wir setzen dort einen höheren Wert (maximal 255, ca. 1/256).
//
// HeartGold/SoulSilver: bekannte feste Stellen.
// Diamant/Perl/Platin und Schwarz/Weiß: Wir suchen die Stelle über ihr Byte-Muster
// (Thumb-Code "cmp r0,#8 / bcs / movs r0,#1", Quelle: Project Pokemon Forum).
//
// Sicherheit: Es wird nur geschrieben, wenn die Stelle eindeutig gefunden wird
// und dort der Originalwert 8 steht. Sonst bleibt die ROM unverändert.
object ShinyPatch {

	class Result(val ok: Boolean, val text: String)

	// Spiele-Kennungen (die ersten drei Zeichen der Game-ID)
	private val GEN4 = mapOf(
		"ADA" to "Diamant", "APA" to "Perl", "CPU" to "Platin",
		"IPK" to "HeartGold", "IPG" to "SoulSilver"
	)
	private val GEN5 = mapOf(
		"IRB" to "Schwarz", "IRA" to "Weiß", "IRE" to "Schwarz 2", "IRD" to "Weiß 2"
	)

	fun isSupported(code: String): Boolean = GEN4.containsKey(code.take(3)) || GEN5.containsKey(code.take(3))

	// Bekannte feste Position bei HeartGold/SoulSilver
	private fun hgssOffset(code: String): Int = when (code) {
		"IPKJ", "IPGJ" -> 0x6FAC0 // japanisch
		"IPKS" -> 0x70078          // spanisch HeartGold
		"IPKK" -> 0x7017C          // koreanisch HeartGold
		"IPGK" -> 0x70174          // koreanisch SoulSilver
		else -> 0x70080            // US und die meisten anderen
	}

	private val PATTERN_LONG = byteArrayOf(0x08, 0x28, 0x01, 0xD2.toByte(), 0x01, 0x20)
	private val PATTERN_SHORT = byteArrayOf(0x08, 0x28, 0x01, 0xD2.toByte())

	// Alle Fundstellen eines Musters im Code
	private fun findAll(data: ByteArray, p: ByteArray): List<Int> {
		val hits = ArrayList<Int>()
		var i = 0
		while (i <= data.size - p.size) {
			var ok = true
			for (j in p.indices) {
				if (data[i + j] != p[j]) { ok = false; break }
			}
			if (ok) hits.add(i)
			i++
		}
		return hits
	}

	// Patcht die Datei. rate: 0..255 (Chance = rate / 65536)
	fun apply(file: File, rate: Int): Result {
		if (!file.name.endsWith(".nds", ignoreCase = true)) {
			return Result(false, "Shiny-Patch gibt es nur für Pokémon-DS-Spiele. Bei diesem Spiel wurde er übersprungen.")
		}
		val tmp = File(file.parentFile, file.name + ".shiny")
		var rom: NDSRom? = null
		try {
			rom = NDSRom(file.absolutePath)
			val code = rom.code
			if (!isSupported(code)) {
				return Result(false, "Shiny-Patch: dieses Spiel ($code) wird nicht unterstützt.")
			}
			val arm9 = rom.getARM9()

			// Stelle bestimmen
			var offset = -1
			if (code.startsWith("IPK") || code.startsWith("IPG")) {
				val o = hgssOffset(code)
				if (o < arm9.size && (arm9[o].toInt() and 0xFF) == 8) offset = o
			}
			if (offset < 0) {
				var hits = findAll(arm9, PATTERN_LONG)
				if (hits.size != 1) hits = findAll(arm9, PATTERN_SHORT)
				if (hits.size == 1) {
					offset = hits[0]
				} else {
					return Result(false, "Shiny-Patch nicht angewendet: Stelle im Spielcode nicht eindeutig gefunden (${hits.size} Treffer, Version $code). Die ROM bleibt unverändert.")
				}
			}
			val before = arm9[offset].toInt() and 0xFF
			if (before != 8) {
				return Result(false, "Shiny-Patch nicht angewendet: an der Stelle steht $before statt 8 (Version $code). Die ROM bleibt unverändert.")
			}
			arm9[offset] = rate.toByte()
			rom.writeARM9(arm9)
			rom.saveTo(tmp.absolutePath)
			rom.closeROM()
			rom = null
			if (!tmp.renameTo(file)) {
				tmp.copyTo(file, overwrite = true)
				tmp.delete()
			}
			val experimental = if (GEN5.containsKey(code.take(3))) " (bei Schwarz/Weiß noch ungetestet)" else ""
			return Result(true, "Shiny-Patch angewendet (Version $code, Wert 8 auf $rate)$experimental.")
		} catch (e: Exception) {
			tmp.delete()
			return Result(false, "Shiny-Patch fehlgeschlagen: ${e.message}")
		} finally {
			try { rom?.closeROM() } catch (e: Exception) { }
		}
	}
}
