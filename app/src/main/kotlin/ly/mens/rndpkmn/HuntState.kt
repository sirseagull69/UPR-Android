package ly.mens.rndpkmn

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

// Gemeinsamer Zustand der Shiny-Jagd (App-Oberfläche und Hintergrunddienst).
// Wird in SharedPreferences gesichert, damit der Zähler einen Neustart übersteht.
object HuntState {
	private const val PREFS = "shinyhunt"

	var count by mutableStateOf(0)
	var target by mutableStateOf("")
	var odds by mutableStateOf(256)          // 1 zu odds
	var sensitivity by mutableStateOf(72)    // Schwelle in Prozent
	var running by mutableStateOf(false)
	var calibrated by mutableStateOf(false)
	var inBattle by mutableStateOf(false)
	var similarity by mutableStateOf(0)
	var status by mutableStateOf("Bereit.")
	var history by mutableStateOf(listOf<String>())

	private var loaded = false
	private val main = Handler(Looper.getMainLooper())

	fun load(ctx: Context) {
		if (loaded) return
		loaded = true
		val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		count = p.getInt("count", 0)
		target = p.getString("target", "") ?: ""
		odds = p.getInt("odds", 256)
		sensitivity = p.getInt("sens", 72).coerceIn(50, 95)
		history = (p.getString("history", "") ?: "").split('\n').filter { it.isNotBlank() }
	}

	fun save(ctx: Context) {
		ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
			.putInt("count", count)
			.putString("target", target)
			.putInt("odds", odds)
			.putInt("sens", sensitivity)
			.putString("history", history.joinToString("\n"))
			.apply()
	}

	// Änderungen immer auf dem Hauptthread, damit die Oberfläche sauber mitkommt
	fun post(block: () -> Unit) {
		if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
	}

	fun add(ctx: Context, n: Int) {
		count = (count + n).coerceAtLeast(0)
		save(ctx)
	}

	// Wahrscheinlichkeit, bei so vielen Begegnungen schon mindestens ein Shiny gesehen zu haben
	fun chance(): Double = 1.0 - (1.0 - 1.0 / odds).pow(count.toDouble())

	fun chanceText(): String = "${(chance() * 100).toInt()} %"

	fun found(ctx: Context) {
		val date = SimpleDateFormat("dd.MM.yy", Locale.GERMANY).format(Date())
		val name = target.ifBlank { "Shiny" }
		history = listOf("$name · $count Begegnungen · $date") + history
		count = 0
		save(ctx)
	}
}
