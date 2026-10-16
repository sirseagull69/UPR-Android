package ly.mens.rndpkmn.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.dabomstew.pkrandom.RandomSource
import com.dabomstew.pkrandom.romhandlers.Abstract3DSRomHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.mens.rndpkmn.BannerPatch
import ly.mens.rndpkmn.ShinyPatch
import ly.mens.rndpkmn.isRomFile
import ly.mens.rndpkmn.loadFromUri
import ly.mens.rndpkmn.saveToUri
import ly.mens.rndpkmn.settings.RandomizerSettings
import java.io.File
import kotlin.math.roundToInt

// Oberfläche im Stil eines DS-Systemmenüs: eckige Kästen mit hartem Schatten, Cursor-Pfeil,
// Häkchen-Kästchen, dichte Listen. Die Logik darunter benutzt den Randomizer-Kern (RandomizerSettings).

private const val PREFS = "soulsilver"
private const val KEY_ROM = "lastRom"
private const val KEY_OUTDIR = "outDir"
private const val KEY_LOGAUTO = "logAuto"
private const val KEY_RUNNO = "runNo"
private const val KEY_RUNNAME = "runName"

private val Bg = Color(0xFF1B2038)
private val Panel = Color(0xFFECE6D2)
private val PanelDash = Color(0xFFD9D2BA)
private val Ink = Color(0xFF17192B)
private val Ink2 = Color(0xFF4B4D63)
private val Red = Color(0xFFD6382B)
private val RedText = Color(0xFFFFF6EE)
private val Muted = Color(0xFF9AA3C9)
private val Mono = FontFamily.Monospace

private fun safeName(s: String?): String =
	(s ?: "Pokemon").replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').removePrefix("Pokemon_").ifEmpty { "Pokemon" }

@Composable
fun SoulSilverApp() {
	MaterialTheme(
		colors = darkColors(primary = Red, onPrimary = RedText, background = Bg, onBackground = Panel, surface = Bg, onSurface = Panel),
		typography = Typography(defaultFontFamily = Mono)
	) {
		Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Panel) { SoulSilverScreen() }
	}
}

// Kasten mit dickem Rand und hartem Schatten (Inhalt dunkel auf Creme)
@Composable
private fun RetroBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
	val shadow = 4.dp
	Column(
		modifier
			.fillMaxWidth()
			.padding(end = shadow, bottom = shadow)
			.drawBehind {
				val s = shadow.toPx()
				drawRect(Ink, topLeft = Offset(s, s), size = size)
			}
			.background(Panel)
			.border(3.dp, Ink, RectangleShape)
			.padding(horizontal = 12.dp, vertical = 2.dp)
	) {
		CompositionLocalProvider(LocalContentColor provides Ink) { content() }
	}
}

@Composable
private fun Heading(text: String) {
	Text(text.uppercase(), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
}

@Composable
private fun Dash() {
	Box(Modifier.fillMaxWidth().height(2.dp).background(PanelDash))
}

// Quadratisches Kästchen (Haken oder Auswahl)
@Composable
private fun Box22(on: Boolean, radio: Boolean) {
	Box(
		Modifier.size(22.dp).background(Color.White).border(3.dp, Ink, RectangleShape),
		contentAlignment = Alignment.Center
	) {
		if (on) {
			if (radio) Box(Modifier.size(10.dp).background(Red))
			else Box(Modifier.fillMaxSize().background(Red), contentAlignment = Alignment.Center) {
				Text("✓", color = RedText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
			}
		}
	}
}

// Eine Listenzeile mit Cursor-Pfeil, Kästchen, Titel und Hinweis
@Composable
private fun Item(
	label: String, on: Boolean, hint: String? = null, radio: Boolean = false,
	enabled: Boolean = true, first: Boolean = false, onClick: () -> Unit
) {
	if (!first) Dash()
	Row(
		Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }.padding(vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp)
	) {
		Text(if (on) "▶" else " ", color = Red, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
		Box22(on, radio)
		Column(Modifier.weight(1f)) {
			Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
			if (hint != null) Text(hint, color = Ink2, fontSize = 12.sp)
		}
	}
}

@Composable
private fun RetroButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
	val shadow = 3.dp
	Box(
		Modifier
			.padding(end = shadow, bottom = shadow)
			.drawBehind {
				val s = shadow.toPx()
				drawRect(if (enabled) Ink else Ink2, topLeft = Offset(s, s), size = size)
			}
			.background(Color.White)
			.border(3.dp, Ink, RectangleShape)
			.clickable(enabled = enabled) { onClick() }
			.padding(horizontal = 12.dp, vertical = 6.dp)
	) {
		Text(text, color = if (enabled) Ink else Ink2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
	}
}

@Composable
private fun Tag(text: String, on: Boolean, onClick: () -> Unit) {
	val shadow = 3.dp
	Box(
		Modifier
			.padding(end = shadow, bottom = shadow)
			.drawBehind {
				val s = shadow.toPx()
				drawRect(Ink, topLeft = Offset(s, s), size = size)
			}
			.background(if (on) Red else Color.White)
			.border(3.dp, Ink, RectangleShape)
			.clickable { onClick() }
			.padding(horizontal = 12.dp, vertical = 6.dp)
	) {
		Text(text, color = if (on) RedText else Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
	}
}

@Composable
private fun LevelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
	Column(Modifier.padding(vertical = 8.dp)) {
		Text(label + ": " + (if (value > 0) "+" else "") + value + " %", fontWeight = FontWeight.Bold, fontSize = 14.sp)
		Slider(
			value = value.toFloat(),
			onValueChange = { onChange(((it / 5f).roundToInt() * 5).coerceIn(-30, 30)) },
			valueRange = -30f..30f,
			steps = 11,
			colors = SliderDefaults.colors(thumbColor = Red, activeTrackColor = Red, inactiveTrackColor = PanelDash)
		)
	}
}

@Composable
private fun retroFieldColors() = TextFieldDefaults.outlinedTextFieldColors(
	textColor = Ink, backgroundColor = Color.White, cursorColor = Red,
	focusedBorderColor = Ink, unfocusedBorderColor = Ink,
	focusedLabelColor = Ink, unfocusedLabelColor = Ink2
)

@Composable
fun SoulSilverScreen() {
	val ctx = LocalContext.current
	val scope = rememberCoroutineScope()
	val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

	var options by remember { mutableStateOf(NuzlockeOptions.load(prefs)) }
	fun update(o: NuzlockeOptions) {
		options = o
		o.saveTo(prefs)
	}

	var romLoaded by remember { mutableStateOf(false) }
	var romInfo by remember { mutableStateOf("Noch kein ROM gewählt.") }
	var seed by remember { mutableStateOf("") }
	var status by remember { mutableStateOf("") }
	var busy by remember { mutableStateOf(false) }
	var logReady by remember { mutableStateOf(false) }
	var showMore by rememberSaveable { mutableStateOf(false) }
	var showAdvanced by rememberSaveable { mutableStateOf(false) }
	var settingsString by remember { mutableStateOf("") }
	var outDir by remember { mutableStateOf(prefs.getString(KEY_OUTDIR, null)) }
	var logAuto by remember { mutableStateOf(prefs.getBoolean(KEY_LOGAUTO, false)) }
	var lastSaved by remember { mutableStateOf<Uri?>(null) }
	var runName by remember { mutableStateOf(prefs.getString(KEY_RUNNAME, "") ?: "") }

	fun outDirName(): String {
		val u = outDir ?: return "nicht gewählt"
		return DocumentFile.fromTreeUri(ctx, Uri.parse(u))?.name ?: "gewählt"
	}

	// Beim Start: zuletzt benutztes ROM automatisch laden
	LaunchedEffect(Unit) {
		withContext(Dispatchers.IO) {
			if (RandomizerSettings.handler != null) {
				romLoaded = true
				romInfo = "Geladen: ${RandomizerSettings.romName}"
				if (seed.isEmpty()) seed = try { RandomizerSettings.currentSeed.toString(16) } catch (e: Exception) { "" }
			} else {
				val name = prefs.getString(KEY_ROM, null)
				if (name != null) {
					busy = true
					status = "Lade letztes ROM …"
					val file = File(ctx.filesDir, name)
					if (file.exists() && file.isRomFile && RandomizerSettings.loadRom(file)) {
						romLoaded = true
						romInfo = "Geladen: ${RandomizerSettings.romName}"
						seed = RandomizerSettings.currentSeed.toString(16)
						status = "Bereit."
					} else {
						status = ""
					}
					busy = false
				}
			}
		}
	}

	// 1) ROM auswählen und laden
	val openRom = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
		if (uri == null) return@rememberLauncherForActivityResult
		scope.launch(Dispatchers.IO) {
			busy = true
			status = "Lade ROM …"
			val name = (DocumentFile.fromSingleUri(ctx, uri)?.name ?: uri.lastPathSegment ?: "rom.nds").substringAfter(':')
			val file = File(ctx.filesDir, name)
			try {
				file.delete() // immer frisch kopieren
				ctx.loadFromUri(uri, file)
			} catch (e: Exception) {
				status = "Die Datei konnte nicht gelesen werden."
				busy = false
				return@launch
			}
			if (file.isRomFile && RandomizerSettings.loadRom(file)) {
				romLoaded = true
				romInfo = "Geladen: ${RandomizerSettings.romName}"
				seed = RandomizerSettings.currentSeed.toString(16)
				prefs.edit().putString(KEY_ROM, name).apply()
				status = "Bereit."
			} else {
				romLoaded = false
				romInfo = "Noch kein ROM gewählt."
				status = "Das ist kein gültiges ROM. Bei 3DS-Spielen muss es entschlüsselt sein."
			}
			busy = false
		}
	}

	// 2) Ausgabe-Ordner wählen (einmalig)
	val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
		if (uri == null) return@rememberLauncherForActivityResult
		try {
			ctx.contentResolver.takePersistableUriPermission(
				uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			)
		} catch (e: Exception) {
			// Dauerhafte Berechtigung nicht möglich: der Ordner gilt dann nur bis zum App-Ende
		}
		outDir = uri.toString()
		prefs.edit().putString(KEY_OUTDIR, outDir).apply()
		status = "Ausgabe-Ordner gesetzt."
	}

	// Kernablauf: randomisieren, optional Shiny, speichern, optional Log
	fun runRandomize(fileUri: Uri?, name: String) {
		scope.launch(Dispatchers.IO) {
			busy = true
			status = "Randomisiere … das kann einen Moment dauern."
			lastSaved = null
			val file = File(ctx.filesDir, name)
			val usedSeed = seed.trim()
			if (RandomizerSettings.saveRom(file)) {
				val shiny = if (options.shinyOn) ShinyPatch.apply(file, options.shinyRate) else null
				// Dateiname auch als Titel in die ROM schreiben (den zeigt der Emulator an)
				val banner = BannerPatch.apply(file, name.substringBeforeLast('.'), "Nuzlocke")
				var target: Uri? = fileUri
				val dir = outDir?.let { DocumentFile.fromTreeUri(ctx, Uri.parse(it)) }
				if (target == null && dir != null && dir.canWrite()) {
					target = dir.createFile("application/octet-stream", name)?.uri
				}
				if (target == null) {
					status = "Randomisiert, aber der Ausgabe-Ordner ist nicht erreichbar. Bitte neu wählen."
				} else {
					status = try {
						ctx.saveToUri(target, file)
						lastSaved = target
						prefs.edit().putInt(KEY_RUNNO, prefs.getInt(KEY_RUNNO, 1) + 1).apply()
						var text = "Fertig: $name (Seed $usedSeed)"
						if (shiny != null) text += "\n" + shiny.text
						if (!banner.ok) text += "\n" + banner.text
						if (fileUri == null && logAuto && dir != null) {
							val logName = name.substringBeforeLast('.') + "-LOG.txt"
							val logUri = dir.createFile("text/plain", logName)?.uri
							if (logUri != null) {
								ctx.contentResolver.openOutputStream(logUri).use { out ->
									if (out != null) RandomizerSettings.outputLog.writeTo(out)
								}
								text += "\nLog gespeichert: $logName"
							}
						}
						text
					} catch (e: Exception) {
						"Randomisiert, aber das Speichern im Ordner hat nicht geklappt."
					}
					// Nächster Lauf bekommt automatisch einen neuen Seed
					RandomizerSettings.currentSeed = RandomSource.pickSeed()
					seed = RandomizerSettings.currentSeed.toString(16)
				}
			} else {
				status = "Randomisieren fehlgeschlagen. Das Log zeigt den Grund."
			}
			logReady = true
			ctx.deleteFile(name)
			RandomizerSettings.reloadRomHandler()
			busy = false
		}
	}

	// Ohne Ausgabe-Ordner: Speicherort pro Lauf abfragen
	val saveRom = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
		if (uri == null) return@rememberLauncherForActivityResult
		val name = (DocumentFile.fromSingleUri(ctx, uri)?.name ?: uri.lastPathSegment ?: "random.nds").substringAfter(':')
		runRandomize(uri, name)
	}

	// Log von Hand speichern (Spoiler!)
	val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
		if (uri == null) return@rememberLauncherForActivityResult
		scope.launch(Dispatchers.IO) {
			ctx.contentResolver.openOutputStream(uri).use { out ->
				if (out != null) RandomizerSettings.outputLog.writeTo(out)
			}
		}
	}

	fun startRandomize() {
		if (!RandomizerSettings.updateSeed(seed.trim(), 16)) {
			status = "Der Seed ist ungültig (erlaubt sind 0-9 und a-f)."
			return
		}
		try {
			options.applyTo()
			// Nicht unterstützte Optionen für dieses Spiel automatisch abschalten (wie der PC-Randomizer)
			RandomizerSettings.handler?.let { RandomizerSettings.tweakForRom(it) }
			settingsString = RandomizerSettings.versionString
		} catch (e: Exception) {
			status = "Die Einstellungen konnten nicht gesetzt werden: ${e.message}"
			return
		}
		// Name: "<Lauf> 003 Normal Shiny a1b2c.nds" - die unterscheidbaren Teile stehen vorne
		val base = runName.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().ifEmpty { safeName(RandomizerSettings.romName) }
		val no = "%03d".format(prefs.getInt(KEY_RUNNO, 1))
		val shinyTag = if (options.shinyOn) " Shiny" else ""
		// Dateiendung vom geladenen ROM übernehmen (.nds, .gba, .gbc ...)
		val ext = if (RandomizerSettings.handler is Abstract3DSRomHandler) "cxi"
			else (prefs.getString(KEY_ROM, null) ?: "rom.nds").substringAfterLast('.', "nds")
		val name = "$base $no ${options.difficulty.label}$shinyTag ${seed.trim().take(5)}.$ext"
		if (outDir != null) runRandomize(null, name) else saveRom.launch(name)
	}


	val fieldText = TextStyle(fontFamily = Mono, fontSize = 15.sp)

	Column(Modifier.fillMaxSize()) {
		Column(
			Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp)
		) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
				Text("NUZLOCKE", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
				Text("Randomizer", color = Muted, fontSize = 13.sp)
			}
			Box(Modifier.fillMaxWidth().height(3.dp).background(Panel))

			// --- Spiel und Ausgabe ---
			RetroBox {
				Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Column(Modifier.weight(1f)) {
						Text(if (romLoaded) romInfo.removePrefix("Geladen: ") else "Kein Spiel gewählt", fontWeight = FontWeight.Bold)
						Text(if (romLoaded) "geladen" else "Wähle dein ROM", color = Ink2, fontSize = 12.sp)
					}
					RetroButton(if (romLoaded) "Wechseln" else "ROM wählen", enabled = !busy) { openRom.launch("*/*") }
				}
				Dash()
				Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Column(Modifier.weight(1f)) {
						Text("Speichern in", color = Ink2, fontSize = 12.sp)
						Text(outDirName(), fontWeight = FontWeight.Bold)
					}
					RetroButton("Ändern", enabled = !busy) { pickDir.launch(null) }
				}
				if (outDir != null) Item("Log (Spoiler!) automatisch mitspeichern", logAuto) {
					logAuto = !logAuto
					prefs.edit().putBoolean(KEY_LOGAUTO, logAuto).apply()
				}
			}

			// --- Schwierigkeit ---
			Heading("Schwierigkeit")
			RetroBox {
				Difficulty.values().forEachIndexed { i, d ->
					Item(d.label, options.difficulty == d, radio = true, first = i == 0) { update(options.copy(difficulty = d)) }
				}
			}
			Text(options.difficulty.info, color = Muted, fontSize = 12.sp)

			// --- Shiny ---
			Heading("Shiny")
			RetroBox {
				Item(
					"Shiny-Chance erhöhen", options.shinyOn, first = true,
					hint = "Diamant, Perl, Platin, HeartGold, SoulSilver, Schwarz/Weiß"
				) { update(options.copy(shinyOn = !options.shinyOn)) }
				if (options.shinyOn) {
					Row(Modifier.padding(start = 24.dp, top = 2.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						listOf("1/1024" to 64, "1/512" to 128, "1/256" to 255).forEach { (label, value) ->
							Tag(label, options.shinyRate == value) { update(options.copy(shinyRate = value)) }
						}
					}
				}
			}

			// --- Spielkomfort ---
			Heading("Komfort")
			RetroBox {
				Item("Schnellster Text", options.fastText, first = true) { update(options.copy(fastText = !options.fastText)) }
				Item("Schnellere HP- und EXP-Balken", options.fastBars) { update(options.copy(fastBars = !options.fastBars)) }
				Item("Nationaler Dex von Anfang an", options.nationalDex) { update(options.copy(nationalDex = !options.nationalDex)) }
				Item("Entwicklungen entschärfen", options.easyEvolutions, "Tausch und Zeit werden normal, hohe Level sinken") {
					update(options.copy(easyEvolutions = !options.easyEvolutions))
				}
				Item("Fangrate anheben", options.higherCatchRate, "Schwere Pokémon leichter fangen") {
					update(options.copy(higherCatchRate = !options.higherCatchRate))
				}
			}

			// --- Mehr Zufall ---
			Text(
				if (showMore) "▲ Weniger" else "▼ Mehr Zufall",
				modifier = Modifier.clickable { showMore = !showMore }.padding(vertical = 4.dp),
				fontWeight = FontWeight.Bold
			)
			if (showMore) {
				Heading("Wilde Pokémon")
				RetroBox {
					listOf("Pro Gebiet 1:1", "Global 1:1", "Komplett zufällig").forEachIndexed { i, l ->
						Item(l, options.wildMapping == i, radio = true, first = i == 0,
							hint = when (i) {
								0 -> "Jedes Pokémon wird in einem Gebiet immer durch dasselbe ersetzt"
								1 -> "Jedes Pokémon wird überall durch dasselbe ersetzt"
								else -> "Jede Begegnung einzeln zufällig"
							}) { update(options.copy(wildMapping = i)) }
					}
				}
				RetroBox {
					Item(
						"Stärke egal", options.wildAnyStrength, first = true,
						hint = "Auch früh starke und fertig entwickelte Pokémon. Sonst bleibt es so schwach wie im Original."
					) { update(options.copy(wildAnyStrength = !options.wildAnyStrength)) }
					Item(
						"Legendäre erlaubt", options.allowLegendaries,
						hint = "Wild und bei Trainern. Sonst bleiben sie gesperrt."
					) { update(options.copy(allowLegendaries = !options.allowLegendaries)) }
				}
				Heading("Zusätzlich zufällig")
				RetroBox {
					Item("Attacken der Pokémon", options.randomMoves, first = true) { update(options.copy(randomMoves = !options.randomMoves)) }
					Item("TMs", options.randomTms) { update(options.copy(randomTms = !options.randomTms)) }
					Item("Shops", options.randomShops, "Sinnvolle Items, Preise ausgeglichen") { update(options.copy(randomShops = !options.randomShops)) }
					Item("Items auf dem Boden mischen", options.shuffleFieldItems) { update(options.copy(shuffleFieldItems = !options.shuffleFieldItems)) }
					Item("Pickup-Items", options.randomPickup) { update(options.copy(randomPickup = !options.randomPickup)) }
					Item("Tausch-NPCs im Spiel", options.randomTrades) { update(options.copy(randomTrades = !options.randomTrades)) }
					Item("Fähigkeiten", options.randomAbilities) { update(options.copy(randomAbilities = !options.randomAbilities)) }
					Item("Typen der Pokémon", options.randomTypes) { update(options.copy(randomTypes = !options.randomTypes)) }
					Item("Basiswerte mischen", options.shuffleBaseStats) { update(options.copy(shuffleBaseStats = !options.shuffleBaseStats)) }
					Dash()
					LevelSlider("Trainer-Level zusätzlich", options.trainerLevelPct) { update(options.copy(trainerLevelPct = it)) }
					LevelSlider("Wilde Pokémon Level", options.wildLevelPct) { update(options.copy(wildLevelPct = it)) }
				}
			}

			// --- Dein Lauf ---
			Heading("Dein Lauf")
			RetroBox {
				Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
					OutlinedTextField(
						value = runName,
						onValueChange = {
							runName = it
							prefs.edit().putString(KEY_RUNNAME, it).apply()
						},
						label = { Text("Name (optional)") },
						modifier = Modifier.fillMaxWidth(),
						singleLine = true, shape = RectangleShape, textStyle = fieldText, colors = retroFieldColors()
					)
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						OutlinedTextField(
							value = seed,
							onValueChange = { seed = it },
							label = { Text("Seed") },
							modifier = Modifier.weight(1f),
							singleLine = true, shape = RectangleShape, textStyle = fieldText, colors = retroFieldColors()
						)
						RetroButton("Würfeln", enabled = romLoaded && !busy) {
							RandomizerSettings.currentSeed = RandomSource.pickSeed()
							seed = RandomizerSettings.currentSeed.toString(16)
						}
					}
				}
			}

			if (status.isNotEmpty()) Text(status, color = Muted, fontSize = 13.sp)

			lastSaved?.let { uri ->
				RetroButton("Mit Emulator öffnen") {
					try {
						val i = Intent(Intent.ACTION_VIEW).apply {
							setDataAndType(uri, "application/octet-stream")
							addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
						}
						ctx.startActivity(Intent.createChooser(i, "Öffnen mit"))
					} catch (e: Exception) {
						status = "Keine App gefunden, die die ROM direkt öffnen kann. Starte sie im Emulator."
					}
				}
			}

			if (logReady && !(outDir != null && logAuto)) {
				RetroButton("Log speichern (Spoiler!)") { saveLog.launch("Nuzlocke-log.txt") }
			}

			// --- Erweitert ---
			Text(
				if (showAdvanced) "▲ Erweitert ausblenden" else "▼ Erweitert (Settings-String)",
				modifier = Modifier.clickable { showAdvanced = !showAdvanced }.padding(vertical = 4.dp),
				fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Muted
			)
			if (showAdvanced) {
				Text("Settings-String plus Seed ergeben genau dieses Spiel.", color = Muted, fontSize = 12.sp)
				RetroBox {
					Text(
						settingsString.ifEmpty { "(erscheint nach dem ersten Lauf)" },
						modifier = Modifier.padding(vertical = 10.dp), fontSize = 12.sp
					)
				}
			}
		}

		// --- Startleiste unten ---
		Column(
			Modifier.fillMaxWidth().background(Bg).border(3.dp, Panel, RectangleShape).padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Red, backgroundColor = Ink)
			val enabled = romLoaded && !busy
			Box(
				Modifier
					.fillMaxWidth()
					.padding(end = 4.dp, bottom = 4.dp)
					.drawBehind {
						val s = 4.dp.toPx()
						drawRect(Color.Black, topLeft = Offset(s, s), size = size)
					}
					.background(if (enabled) Red else Ink2)
					.border(3.dp, Panel, RectangleShape)
					.clickable(enabled = enabled) { startRandomize() }
					.padding(vertical = 12.dp),
				contentAlignment = Alignment.Center
			) {
				Text("RANDOMISIEREN", color = RedText, fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = 1.sp)
			}
		}
	}
}
