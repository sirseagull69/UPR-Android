package ly.mens.rndpkmn

import com.dabomstew.pkrandom.newnds.NDSRom
import java.io.File

// Ändert die Shiny-Rate in einer HeartGold/SoulSilver-ROM.
// Idee und Offsets stammen vom Tool "hgss-shiny-modifier" (choogiesaur, MIT-Lizenz):
// Im entpackten ARM9-Code steht an einer festen Stelle ein einzelnes Byte,
// das die Shiny-Chance bestimmt (Original: 8 von 65536). Wir setzen dort einen höheren Wert.
object ShinyPatch {

	class Result(val ok: Boolean, val text: String)

	// Position des Bytes je nach Spielversion (Game-ID). Die meisten Versionen nutzen 0x70080.
	private fun offsetFor(code: String): Int = when (code) {
		"IPKJ", "IPGJ" -> 0x6FAC0 // japanisch
		"IPKS" -> 0x70078          // spanisch HeartGold
		"IPKK" -> 0x7017C          // koreanisch HeartGold
		"IPGK" -> 0x70174          // koreanisch SoulSilver
		else -> 0x70080            // US und die meisten anderen
	}

	// Patcht die Datei. rate: 0..255 (Chance = rate / 65536)
	fun apply(file: File, rate: Int): Result {
		val tmp = File(file.parentFile, file.name + ".shiny")
		var rom: NDSRom? = null
		try {
			rom = NDSRom(file.absolutePath)
			val code = rom.code
			val offset = offsetFor(code)
			val arm9 = rom.getARM9()
			if (offset >= arm9.size) {
				return Result(false, "Shiny-Patch nicht möglich: Stelle im Spielcode nicht gefunden (Version $code).")
			}
			val before = arm9[offset].toInt() and 0xFF
			arm9[offset] = rate.toByte()
			rom.writeARM9(arm9)
			rom.saveTo(tmp.absolutePath)
			rom.closeROM()
			rom = null
			if (!file.delete() || !tmp.renameTo(file)) {
				return Result(false, "Shiny-Patch: die fertige Datei konnte nicht ersetzt werden.")
			}
			val warn = if (before != 8) " Achtung: Originalwert war $before statt 8, die Version wird evtl. nicht unterstützt." else ""
			return Result(true, "Shiny-Patch angewendet (Version $code, Wert $before auf $rate).$warn")
		} catch (e: Exception) {
			tmp.delete()
			return Result(false, "Shiny-Patch fehlgeschlagen: ${e.message}")
		} finally {
			try { rom?.closeROM() } catch (e: Exception) { }
		}
	}
}
