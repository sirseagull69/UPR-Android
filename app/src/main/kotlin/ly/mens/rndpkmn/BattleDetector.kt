package ly.mens.rndpkmn

import kotlin.math.abs
import kotlin.math.max

// Erkennt Kämpfe anhand eines winzigen Bildschirmbilds (ca. 160 Pixel breit).
// Idee: Der Kampfbildschirm sieht immer fast gleich aus (Hintergrund, KP-Kästen, Textbox).
// Einmal "merken" (Kalibrieren), danach wird jedes Bild mit diesem Referenzbild verglichen.
// Kein Texterkennen, keine KI: nur ein paar tausend Pixelvergleiche pro Bild.
class BattleDetector(val w: Int, val h: Int) {

	enum class Event { NONE, CALIBRATED, RECALIBRATED, CALIBRATE_FAILED, ENCOUNTER, BATTLE_END }

	var ref: IntArray? = null
		private set
	// Bereich ohne schwarze Ränder (x0..x1, y0..y1)
	private var x0 = 0
	private var y0 = 0
	private var x1 = w - 1
	private var y1 = h - 1

	var inBattle = false
		private set
	var lastSimilarity = 0f
		private set

	// Schwelle für "Kampf" (0..1). Wird über die App eingestellt.
	var enterThreshold = 0.72f
	private val exitThreshold get() = enterThreshold - 0.25f

	private var hi = 0
	private var lo = 0
	private var calibratePending = false

	fun requestCalibrate() { calibratePending = true }

	fun setReference(frame: IntArray): Boolean {
		if (frame.size != w * h) return false
		// Rahmen suchen: alles, was nicht fast schwarz ist
		var minX = w; var minY = h; var maxX = -1; var maxY = -1
		for (y in 0 until h) for (x in 0 until w) {
			if (brightness(frame[y * w + x]) > 24) {
				if (x < minX) minX = x
				if (x > maxX) maxX = x
				if (y < minY) minY = y
				if (y > maxY) maxY = y
			}
		}
		if (maxX < 0) return false
		val area = (maxX - minX + 1) * (maxY - minY + 1)
		if (area < w * h / 10) return false
		x0 = minX; y0 = minY; x1 = maxX; y1 = maxY
		ref = frame.copyOf()
		return true
	}

	fun similarity(frame: IntArray): Float {
		val r = ref ?: return 0f
		var same = 0
		var total = 0
		for (y in y0..y1) {
			val row = y * w
			for (x in x0..x1) {
				val a = frame[row + x]
				val b = r[row + x]
				val d = max(
					abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
					max(abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF)))
				)
				if (d <= 28) same++
				total++
			}
		}
		return if (total == 0) 0f else same.toFloat() / total
	}

	// Wird für jedes Bild (ca. 4 pro Sekunde) aufgerufen
	fun onFrame(frame: IntArray): Event {
		if (calibratePending) {
			calibratePending = false
			val wasInBattle = inBattle
			return if (setReference(frame)) {
				inBattle = true
				hi = 0; lo = 0
				lastSimilarity = 1f
				if (wasInBattle) Event.RECALIBRATED else Event.CALIBRATED
			} else Event.CALIBRATE_FAILED
		}
		if (ref == null) return Event.NONE
		val s = similarity(frame)
		lastSimilarity = s
		if (!inBattle) {
			if (s >= enterThreshold) hi++ else hi = 0
			if (hi >= 2) { // 0,5 s sicher im Kampf
				inBattle = true; lo = 0
				return Event.ENCOUNTER
			}
		} else {
			if (s < exitThreshold) lo++ else lo = 0
			if (lo >= 12) { // 3 s sicher draußen
				inBattle = false; hi = 0
				return Event.BATTLE_END
			}
		}
		return Event.NONE
	}

	private fun brightness(c: Int): Int = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
}
