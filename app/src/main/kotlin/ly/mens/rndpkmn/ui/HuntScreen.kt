package ly.mens.rndpkmn.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ly.mens.rndpkmn.HuntService
import ly.mens.rndpkmn.HuntState
import kotlin.math.roundToInt

// Shiny-Jagd: zählt Begegnungen automatisch über eine winzige Bildschirmaufnahme.
@Composable
fun HuntScreen() {
	val ctx = LocalContext.current
	remember { HuntState.load(ctx); true }

	val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
		val data = res.data
		if (res.resultCode == Activity.RESULT_OK && data != null) {
			ContextCompat.startForegroundService(ctx, HuntService.startIntent(ctx, res.resultCode, data))
		} else {
			HuntState.status = "Bildschirmaufnahme abgelehnt."
		}
	}
	fun requestCapture() {
		val mpm = ctx.getSystemService(MediaProjectionManager::class.java)!!
		capture.launch(mpm.createScreenCaptureIntent())
	}
	val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestCapture() }
	fun start() {
		HuntState.save(ctx)
		val perm = "android.permission.POST_NOTIFICATIONS"
		if (Build.VERSION.SDK_INT >= 33 &&
			ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED
		) notifPerm.launch(perm) else requestCapture()
	}

	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp)
	) {
		// --- Zähler ---
		RetroBox {
			Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
				Text(HuntState.target.ifBlank { "SHINY-JAGD" }.uppercase(), color = Ink2, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
				Text("${HuntState.count}", fontWeight = FontWeight.Bold, fontSize = 64.sp)
				Text("Begegnungen", color = Ink2, fontSize = 13.sp)
				Spacer(Modifier.height(8.dp))
				Text("Chance bisher: ${HuntState.chanceText()}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
				// Balken
				Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(14.dp).background(androidx.compose.ui.graphics.Color.White)) {
					Box(Modifier.fillMaxHeight().fillMaxWidth(HuntState.chance().toFloat().coerceIn(0f, 1f)).background(Red))
				}
			}
			Dash()
			Row(
				Modifier.fillMaxWidth().padding(vertical = 10.dp),
				horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
			) {
				RetroButton("−1") { HuntState.add(ctx, -1) }
				RetroButton("+1") { HuntState.add(ctx, 1) }
				RetroButton("SHINY!") { HuntState.found(ctx) }
			}
		}

		// --- Steuerung ---
		Heading("Automatik")
		RetroBox {
			Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
				val live = if (HuntState.running) {
					(if (HuntState.inBattle) "IM KAMPF" else "SUCHT") + " · Ähnlichkeit ${HuntState.similarity} %"
				} else "AUS"
				Text("Status: $live", fontWeight = FontWeight.Bold, fontSize = 14.sp)
				Text(HuntState.status, color = Ink2, fontSize = 13.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					if (!HuntState.running) RetroButton("JAGD STARTEN") { start() }
					else {
						RetroButton("STOPPEN") {
							ctx.startService(HuntService.action(ctx, HuntService.ACTION_STOP))
						}
						RetroButton("KAMPF MERKEN (5 s)") {
							ctx.startService(HuntService.action(ctx, HuntService.ACTION_CALIBRATE, 5000L))
						}
					}
				}
			}
			Dash()
			Column(Modifier.padding(vertical = 10.dp)) {
				Text("Empfindlichkeit: ${HuntState.sensitivity} %", fontWeight = FontWeight.Bold, fontSize = 14.sp)
				Text("Zählt er Kämpfe nicht: runter. Zählt er ohne Kampf: hoch.", color = Ink2, fontSize = 12.sp)
				Slider(
					value = HuntState.sensitivity.toFloat(),
					onValueChange = { HuntState.sensitivity = ((it / 2f).roundToInt() * 2).coerceIn(50, 95) },
					onValueChangeFinished = { HuntState.save(ctx) },
					valueRange = 50f..95f,
					colors = SliderDefaults.colors(thumbColor = Red, activeTrackColor = Red, inactiveTrackColor = PanelDash)
				)
			}
		}

		// --- Einstellungen ---
		Heading("Jagd")
		RetroBox {
			Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
				OutlinedTextField(
					value = HuntState.target,
					onValueChange = { HuntState.target = it.take(24); HuntState.save(ctx) },
					label = { Text("Ziel-Pokémon (optional)") },
					singleLine = true,
					textStyle = TextStyle(fontFamily = Mono, fontSize = 15.sp),
					colors = retroFieldColors(),
					modifier = Modifier.fillMaxWidth()
				)
				Text("Shiny-Chance im Spiel", fontWeight = FontWeight.Bold, fontSize = 14.sp)
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					for (o in listOf(8192, 1024, 512, 256)) {
						Tag("1/$o", HuntState.odds == o) { HuntState.odds = o; HuntState.save(ctx) }
					}
				}
				Text("Original 1/8192. Mit Shiny-Patch 1/1024, 1/512 oder 1/256.", color = Ink2, fontSize = 12.sp)
			}
		}

		// --- Anleitung ---
		Heading("So geht's")
		RetroBox {
			Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text("1. JAGD STARTEN, Aufnahme erlauben (ganzer Bildschirm).", fontSize = 13.sp)
				Text("2. Im ersten wilden Kampf: Leiste runterziehen, \"Kampf merken\", Leiste schließen, 3 s warten.", fontSize = 13.sp)
				Text("3. Ab jetzt zählt die App jeden Kampf selbst.", fontSize = 13.sp)
				Text("Tipp: Leiste im Kampf nicht lange offen lassen. Trainerkämpfe zählen mit. Neue Gegend? Einmal neu merken.", color = Ink2, fontSize = 12.sp)
			}
		}

		// --- Verlauf ---
		if (HuntState.history.isNotEmpty()) {
			Heading("Gefangene Shinys")
			RetroBox {
				HuntState.history.forEachIndexed { i, line ->
					if (i > 0) Dash()
					Text("★ $line", modifier = Modifier.padding(vertical = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold)
				}
			}
		}
	}
}
