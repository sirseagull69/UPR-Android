package ly.mens.rndpkmn.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.dabomstew.pkrandom.RandomSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ly.mens.rndpkmn.isRomFile
import ly.mens.rndpkmn.loadFromUri
import ly.mens.rndpkmn.saveToUri
import ly.mens.rndpkmn.settings.RandomizerSettings
import java.io.File

// Eigene, schlanke Oberfläche für den SoulSilver-Randomizer.
// Sie benutzt den vorhandenen Randomizer-Kern (RandomizerSettings) und
// ersetzt nur die Bedienung: ROM wählen, Preset, Seed, ein Button.

private const val PREFS = "soulsilver"       // Name des Speichers für dein Preset
private const val KEY_PRESET = "preset"      // Schlüssel, unter dem der Settings-String liegt

@Composable
fun SoulSilverApp() {
	MaterialTheme(colors = if (isSystemInDarkTheme()) darkColors() else lightColors()) {
		Surface(Modifier.fillMaxSize()) { SoulSilverScreen() }
	}
}

@Composable
fun SoulSilverScreen() {
	val ctx = LocalContext.current
	val scope = rememberCoroutineScope()
	val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

	// Alles, was sich auf dem Bildschirm ändern kann, steht hier als "State".
	var romLoaded by rememberSaveable { mutableStateOf(false) }
	var romInfo by rememberSaveable { mutableStateOf("Noch kein ROM gewählt.") }
	var preset by rememberSaveable { mutableStateOf(prefs.getString(KEY_PRESET, "") ?: "") }
	var seed by rememberSaveable { mutableStateOf("") }
	var status by rememberSaveable { mutableStateOf("") }
	var busy by remember { mutableStateOf(false) }
	var logReady by remember { mutableStateOf(false) }

	// Wendet deinen gespeicherten Settings-String auf den Randomizer an.
	fun applyPreset(): Boolean {
		val text = preset.trim()
		if (text.isEmpty()) return true
		return RandomizerSettings.updateFromString(text)
	}

	// 1) ROM auswählen und in den Randomizer laden
	val openRom = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
		if (uri == null) return@rememberLauncherForActivityResult
		scope.launch(Dispatchers.IO) {
			busy = true
			status = "Lade ROM …"
			val name = DocumentFile.fromSingleUri(ctx, uri)?.name ?: uri.lastPathSegment ?: "rom.nds"
			val file = File(ctx.filesDir, name)
			try {
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
				status = if (applyPreset()) "Bereit." else "ROM geladen, aber der Settings-String ist ungültig."
			} else {
				romLoaded = false
				romInfo = "Noch kein ROM gewählt."
				status = "Das ist kein gültiges ROM."
			}
			busy = false
		}
	}

	// 2) Ergebnis speichern: Android fragt nach Ordner und Dateiname,
	//    danach wird randomisiert und die Datei dorthin kopiert.
	val saveRom = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
		if (uri == null) return@rememberLauncherForActivityResult
		scope.launch(Dispatchers.IO) {
			busy = true
			status = "Randomisiere … das kann einen Moment dauern."
			val name = (DocumentFile.fromSingleUri(ctx, uri)?.name ?: uri.lastPathSegment ?: "SoulSilver-random.nds")
				.substringAfter(':')
			val file = File(ctx.filesDir, name)
			if (RandomizerSettings.saveRom(file)) {
				status = try {
					ctx.saveToUri(uri, file)
					"Fertig: $name"
				} catch (e: Exception) {
					"Randomisiert, aber das Speichern im Ordner hat nicht geklappt."
				}
			} else {
				status = "Randomisieren fehlgeschlagen. Das Log zeigt den Grund."
			}
			logReady = true
			ctx.deleteFile(name)                  // temporäre Kopie wieder löschen
			RandomizerSettings.reloadRomHandler() // für den nächsten Durchlauf vorbereiten
			busy = false
		}
	}

	// 3) Log speichern (verrät alle Gegner und Spawns, also Spoiler!)
	val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
		if (uri == null) return@rememberLauncherForActivityResult
		scope.launch(Dispatchers.IO) {
			ctx.contentResolver.openOutputStream(uri).use { out ->
				if (out != null) RandomizerSettings.outputLog.writeTo(out)
			}
		}
	}

	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Text("SoulSilver Randomizer", style = MaterialTheme.typography.h5)

		// ROM
		Button({ openRom.launch("*/*") }, enabled = !busy) { Text("ROM wählen") }
		Text(romInfo)

		// Preset (dein Nuzlocke-Settings-String), wird dauerhaft gespeichert
		OutlinedTextField(
			value = preset,
			onValueChange = { preset = it },
			label = { Text("Settings-String (Nuzlocke-Preset)") },
			modifier = Modifier.fillMaxWidth(),
			maxLines = 4
		)
		OutlinedButton({
			prefs.edit().putString(KEY_PRESET, preset.trim()).apply()
			status = if (!romLoaded) "Preset gespeichert."
			else if (applyPreset()) "Preset gespeichert und angewendet."
			else "Der Settings-String ist ungültig."
		}) { Text("Preset speichern") }

		// Seed
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

		// Los geht's
		Button({
			if (!applyPreset()) {
				status = "Der Settings-String ist ungültig."
			} else if (!RandomizerSettings.updateSeed(seed.trim(), 16)) {
				status = "Der Seed ist ungültig (erlaubt sind 0-9 und a-f)."
			} else {
				saveRom.launch("SoulSilver-${seed.trim()}.nds")
			}
		}, enabled = romLoaded && !busy, modifier = Modifier.fillMaxWidth()) {
			Text("Randomisieren und speichern")
		}

		if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
		if (status.isNotEmpty()) Text(status)

		if (logReady) {
			OutlinedButton({ saveLog.launch("SoulSilver-log.txt") }) { Text("Log speichern (Spoiler!)") }
		}
	}
}
