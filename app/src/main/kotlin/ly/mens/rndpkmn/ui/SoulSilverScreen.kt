package ly.mens.rndpkmn.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.dabomstew.pkrandom.RandomSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ly.mens.rndpkmn.ShinyPatch
import ly.mens.rndpkmn.isRomFile
import ly.mens.rndpkmn.loadFromUri
import ly.mens.rndpkmn.saveToUri
import ly.mens.rndpkmn.settings.RandomizerSettings
import java.io.File
import kotlin.math.roundToInt

// Eigene, schlanke Oberfläche für den Nuzlocke-Randomizer (SoulSilver / HeartGold).
// Sie benutzt den vorhandenen Randomizer-Kern (RandomizerSettings) und ersetzt nur die Bedienung.

private const val PREFS = "soulsilver"
private const val KEY_ROM = "lastRom"
private const val KEY_OUTDIR = "outDir"
private const val KEY_LOGAUTO = "logAuto"

private fun safeName(s: String?): String =
	(s ?: "Pokemon").replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').removePrefix("Pokemon_").ifEmpty { "Pokemon" }

@Composable
fun SoulSilverApp() {
	MaterialTheme(colors = if (isSystemInDarkTheme()) darkColors() else lightColors()) {
		Surface(Modifier.fillMaxSize()) { SoulSilverScreen() }
	}
}

// Eine Zeile mit Haken und Text; das ganze Feld ist antippbar.
@Composable
private fun CheckRow(label: String, checked: Boolean, hint: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
	Row(
		Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
		verticalAlignment = Alignment.CenterVertically
	) {
		Checkbox(checked = checked, onCheckedChange = { onChange(it) }, enabled = enabled)
		Column {
			Text(label)
			if (hint != null) Text(hint, style = MaterialTheme.typography.caption)
		}
	}
}

@Composable
private fun LevelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
	Column {
		Text(label + ": " + (if (value > 0) "+" else "") + value + " %")
		Slider(
			value = value.toFloat(),
			onValueChange = { onChange(((it / 5f).roundToInt() * 5).coerceIn(-30, 30)) },
			valueRange = -30f..30f,
			steps = 11
		)
	}
}

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
				status = "Das ist kein gültiges ROM."
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
						var text = "Fertig: $name (Seed $usedSeed)"
						if (shiny != null) text += "\n" + shiny.text
						if (fileUri == null && logAuto && dir != null) {
							val logName = name.removeSuffix(".nds") + "-LOG.txt"
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
			settingsString = RandomizerSettings.versionString
		} catch (e: Exception) {
			status = "Die Einstellungen konnten nicht gesetzt werden: ${e.message}"
			return
		}
		val name = "${safeName(RandomizerSettings.romName)}-${seed.trim()}.nds"
		if (outDir != null) runRandomize(null, name) else saveRom.launch(name)
	}

	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Text("Nuzlocke-Randomizer", style = MaterialTheme.typography.h5)

		// --- ROM und Ausgabe ---
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			Button({ openRom.launch("*/*") }, enabled = !busy) { Text(if (romLoaded) "Anderes ROM wählen" else "ROM wählen") }
			Text(romInfo)
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			OutlinedButton({ pickDir.launch(null) }, enabled = !busy) { Text("Ausgabe-Ordner wählen") }
			Text("Ordner: ${outDirName()}")
		}
		if (outDir != null) {
			CheckRow("Log (Spoiler!) automatisch mitspeichern", logAuto) {
				logAuto = it
				prefs.edit().putBoolean(KEY_LOGAUTO, it).apply()
			}
		}

		Divider()

		// --- Schwierigkeit ---
		Text("Schwierigkeit", style = MaterialTheme.typography.subtitle1)
		Row(Modifier.fillMaxWidth()) {
			Difficulty.values().forEach { d ->
				Row(
					Modifier.weight(1f).clickable { update(options.copy(difficulty = d)) },
					verticalAlignment = Alignment.CenterVertically
				) {
					RadioButton(selected = options.difficulty == d, onClick = { update(options.copy(difficulty = d)) })
					Text(d.label)
				}
			}
		}
		Text(options.difficulty.info, style = MaterialTheme.typography.caption)

		// --- Shiny ---
		CheckRow("Shiny-Chance erhöhen", options.shinyOn, "Nur für HeartGold und SoulSilver") {
			update(options.copy(shinyOn = it))
		}
		if (options.shinyOn) {
			listOf("1/1024" to 64, "1/512" to 128, "1/256" to 255).forEach { (label, value) ->
				Row(
					Modifier.clickable { update(options.copy(shinyRate = value)) },
					verticalAlignment = Alignment.CenterVertically
				) {
					RadioButton(selected = options.shinyRate == value, onClick = { update(options.copy(shinyRate = value)) })
					Text(label)
				}
			}
		}

		// --- Spielkomfort ---
		Text("Komfort", style = MaterialTheme.typography.subtitle1)
		CheckRow("Schnellster Text", options.fastText) { update(options.copy(fastText = it)) }
		CheckRow("Schnellere HP- und EXP-Balken", options.fastBars) { update(options.copy(fastBars = it)) }
		CheckRow("Nationaler Dex von Anfang an", options.nationalDex) { update(options.copy(nationalDex = it)) }
		CheckRow(
			"Entwicklungen entschärfen", options.easyEvolutions,
			"Tausch- und Zeit-Entwicklungen werden normal, hohe Level sinken"
		) { update(options.copy(easyEvolutions = it)) }
		CheckRow(
			"Fangrate anheben", options.higherCatchRate,
			"Schwer fangbare Pokémon sind leichter zu fangen"
		) { update(options.copy(higherCatchRate = it)) }

		// --- Mehr Zufall ---
		TextButton({ showMore = !showMore }) {
			Text(if (showMore) "Weniger Optionen ▲" else "Mehr Optionen ▼")
		}
		if (showMore) {
			Text("Zusätzlich zufällig", style = MaterialTheme.typography.subtitle1)
			CheckRow("Attacken der Pokémon", options.randomMoves) { update(options.copy(randomMoves = it)) }
			CheckRow("TMs", options.randomTms) { update(options.copy(randomTms = it)) }
			CheckRow("Items auf dem Boden mischen", options.shuffleFieldItems) { update(options.copy(shuffleFieldItems = it)) }
			CheckRow("Fähigkeiten", options.randomAbilities) { update(options.copy(randomAbilities = it)) }
			CheckRow("Typen der Pokémon", options.randomTypes) { update(options.copy(randomTypes = it)) }
			CheckRow("Basiswerte mischen", options.shuffleBaseStats) { update(options.copy(shuffleBaseStats = it)) }
			LevelSlider("Trainer-Level zusätzlich", options.trainerLevelPct) { update(options.copy(trainerLevelPct = it)) }
			LevelSlider("Wilde Pokémon Level", options.wildLevelPct) { update(options.copy(wildLevelPct = it)) }
		}

		Divider()

		// --- Seed und Start ---
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			OutlinedTextField(
				value = seed,
				onValueChange = { seed = it },
				label = { Text("Seed") },
				modifier = Modifier.weight(1f),
				singleLine = true
			)
			OutlinedButton({
				RandomizerSettings.currentSeed = RandomSource.pickSeed()
				seed = RandomizerSettings.currentSeed.toString(16)
			}, enabled = romLoaded && !busy) { Text("Neu würfeln") }
		}

		Button(
			{ startRandomize() },
			enabled = romLoaded && !busy,
			modifier = Modifier.fillMaxWidth()
		) { Text("Randomisieren und speichern") }

		if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
		if (status.isNotEmpty()) Text(status)

		lastSaved?.let { uri ->
			OutlinedButton({
				try {
					val i = Intent(Intent.ACTION_VIEW).apply {
						setDataAndType(uri, "application/octet-stream")
						addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
					}
					ctx.startActivity(Intent.createChooser(i, "Öffnen mit"))
				} catch (e: Exception) {
					status = "Keine App gefunden, die die ROM direkt öffnen kann. Starte sie im Emulator."
				}
			}) { Text("Mit Emulator öffnen") }
		}

		if (logReady && !(outDir != null && logAuto)) {
			OutlinedButton({ saveLog.launch("Nuzlocke-log.txt") }) { Text("Log speichern (Spoiler!)") }
		}

		// --- Erweitert ---
		TextButton({ showAdvanced = !showAdvanced }) {
			Text(if (showAdvanced) "Erweitert ausblenden ▲" else "Erweitert (Settings-String) ▼")
		}
		if (showAdvanced) {
			Text(
				"Settings-String plus Seed ergeben genau dieses Spiel. Wer beides hat, bekommt dieselbe ROM.",
				style = MaterialTheme.typography.caption
			)
			OutlinedTextField(
				value = settingsString,
				onValueChange = { },
				readOnly = true,
				label = { Text("Settings-String (vom letzten Lauf)") },
				modifier = Modifier.fillMaxWidth(),
				maxLines = 4
			)
		}
	}
}
