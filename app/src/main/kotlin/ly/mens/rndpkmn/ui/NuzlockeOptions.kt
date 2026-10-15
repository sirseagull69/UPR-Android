package ly.mens.rndpkmn.ui

import android.content.SharedPreferences
import com.dabomstew.pkrandom.MiscTweak
import ly.mens.rndpkmn.settings.RandomizerSettings

// Alle Einstellungen, die man in der App sieht, an einer Stelle.
// "applyTo" schreibt sie in den Randomizer-Kern. Dabei wird JEDES Feld gesetzt,
// das wir benutzen. So bleibt nichts vom vorherigen Preset hängen.

enum class Difficulty(val label: String, val info: String) {
	EASY("Leicht", "Starter mit 2 Entwicklungen, Rivale behält seinen Starter, Trainer 10 % schwächer."),
	NORMAL("Normal", "Alles zufällig, aber ähnlich stark wie im Original. Trainer wie im Original."),
	HARD("Hart", "Typ-Teams bei Arenen und Top 4, Trainer 10 % stärker, Bosse mit 1 Extra-Pokémon.")
}

data class NuzlockeOptions(
	val difficulty: Difficulty = Difficulty.NORMAL,
	// Zusatz-Schalter (Zufall)
	val randomMoves: Boolean = false,       // Attacken, die Pokémon beim Levelaufstieg lernen
	val randomTms: Boolean = false,         // Inhalt der TMs
	val shuffleFieldItems: Boolean = false, // Items auf dem Boden mischen
	val randomAbilities: Boolean = false,   // Fähigkeiten
	val randomTypes: Boolean = false,       // Typen der Pokémon
	val shuffleBaseStats: Boolean = false,  // Basiswerte mischen
	// Spielkomfort
	val easyEvolutions: Boolean = true,     // Tausch-/Zeit-Entwicklungen und hohe Level entschärfen
	val higherCatchRate: Boolean = false,   // Mindest-Fangrate anheben
	val fastText: Boolean = true,           // Schnellster Text
	val nationalDex: Boolean = true,        // Nationaler Dex von Anfang an
	val fastBars: Boolean = true,           // Schnellere HP- und EXP-Balken
	val trainerLevelPct: Int = 0,           // zusätzlich zur Schwierigkeit
	val wildLevelPct: Int = 0,
	// Shiny
	val shinyOn: Boolean = false,
	val shinyRate: Int = 255
) {
	fun saveTo(p: SharedPreferences) {
		p.edit()
			.putString("difficulty", difficulty.name)
			.putBoolean("randomMoves", randomMoves)
			.putBoolean("randomTms", randomTms)
			.putBoolean("shuffleFieldItems", shuffleFieldItems)
			.putBoolean("randomAbilities", randomAbilities)
			.putBoolean("randomTypes", randomTypes)
			.putBoolean("shuffleBaseStats", shuffleBaseStats)
			.putBoolean("easyEvolutions", easyEvolutions)
			.putBoolean("higherCatchRate", higherCatchRate)
			.putBoolean("fastText", fastText)
			.putBoolean("nationalDex", nationalDex)
			.putBoolean("fastBars", fastBars)
			.putInt("trainerLevelPct", trainerLevelPct)
			.putInt("wildLevelPct", wildLevelPct)
			.putBoolean("shinyOn", shinyOn)
			.putInt("shinyRate", shinyRate)
			.apply()
	}

	companion object {
		fun load(p: SharedPreferences): NuzlockeOptions {
			val d = NuzlockeOptions()
			val diff = try {
				Difficulty.valueOf(p.getString("difficulty", d.difficulty.name) ?: d.difficulty.name)
			} catch (e: IllegalArgumentException) {
				d.difficulty
			}
			return NuzlockeOptions(
				difficulty = diff,
				randomMoves = p.getBoolean("randomMoves", d.randomMoves),
				randomTms = p.getBoolean("randomTms", d.randomTms),
				shuffleFieldItems = p.getBoolean("shuffleFieldItems", d.shuffleFieldItems),
				randomAbilities = p.getBoolean("randomAbilities", d.randomAbilities),
				randomTypes = p.getBoolean("randomTypes", d.randomTypes),
				shuffleBaseStats = p.getBoolean("shuffleBaseStats", d.shuffleBaseStats),
				easyEvolutions = p.getBoolean("easyEvolutions", d.easyEvolutions),
				higherCatchRate = p.getBoolean("higherCatchRate", d.higherCatchRate),
				fastText = p.getBoolean("fastText", d.fastText),
				nationalDex = p.getBoolean("nationalDex", d.nationalDex),
				fastBars = p.getBoolean("fastBars", d.fastBars),
				trainerLevelPct = p.getInt("trainerLevelPct", d.trainerLevelPct),
				wildLevelPct = p.getInt("wildLevelPct", d.wildLevelPct),
				shinyOn = p.getBoolean("shinyOn", d.shinyOn),
				shinyRate = p.getInt("shinyRate", d.shinyRate)
			)
		}
	}
}

// Die Randomizer-Optionen mit mehreren Auswahlmöglichkeiten bekommen eine Liste
// mit genau einem "true". Diese Hilfsfunktion baut sie: n Möglichkeiten, Nummer idx gewählt.
private fun pick(n: Int, idx: Int) = BooleanArray(n) { it == idx }

fun NuzlockeOptions.applyTo() {
	val hard = difficulty == Difficulty.HARD
	val easy = difficulty == Difficulty.EASY

	with(RandomizerSettings) {
		// Starter: 0 unverändert, 1 eigene Wahl, 2 komplett zufällig, 3 zufällig mit 2 Entwicklungen
		setStartersMod(*pick(4, if (easy) 3 else 2))

		// Wilde Pokémon: 0 unverändert, 1 zufällig, 2 pro Gebiet 1:1, 3 global 1:1
		setWildPokemonMod(*pick(4, 2))
		// Einschränkung: 0 keine, 1 ähnlich stark, 2 alle fangbar, 3 Typ-Gebiete
		setWildPokemonRestrictionMod(*pick(4, 1))
		setBlockWildLegendaries(true)
		setUseTimeBasedEncounters(false)

		// Trainer: 0 unverändert, 1 zufällig, 2 gleichmäßig, 3 gleichmäßig (Hauptspiel),
		// 4 Typ-Teams, 5 Typ-Teams auch für Top 4 und Arenen
		setTrainersMod(*pick(6, if (hard) 5 else 1))
		setTrainersUsePokemonOfSimilarStrength(true)
		setTrainersBlockLegendaries(true)
		setTrainersMatchTypingDistribution(false)
		setRivalCarriesStarterThroughout(easy)
		setBetterTrainerMovesets(hard)
		setTrainersForceFullyEvolved(hard)
		setTrainersForceFullyEvolvedLevel(45)

		val trainerBase = when (difficulty) {
			Difficulty.EASY -> -10
			Difficulty.NORMAL -> 0
			Difficulty.HARD -> 10
		}
		val trainerPct = (trainerBase + trainerLevelPct).coerceIn(-50, 60)
		setTrainersLevelModified(trainerPct != 0)
		setTrainersLevelModifier(trainerPct)
		setAdditionalBossTrainerPokemon(if (hard) 1 else 0)
		setAdditionalImportantTrainerPokemon(0)
		setAdditionalRegularTrainerPokemon(0)

		val wildPct = wildLevelPct.coerceIn(-50, 60)
		setWildLevelsModified(wildPct != 0)
		setWildLevelModifier(wildPct)

		// Statische Pokémon: 0 unverändert, 1 tauschen, 2 komplett zufällig, 3 ähnlich stark
		setStaticPokemonMod(*pick(4, 3))

		// Fangrate
		setUseMinimumCatchRate(higherCatchRate)
		setMinimumCatchRateLevel(3)

		// Entwicklungen entschärfen (Tausch, Zeit, sehr hohe Level)
		setChangeImpossibleEvolutions(easyEvolutions)
		setMakeEvolutionsEasier(easyEvolutions)
		setRemoveTimeBasedEvolutions(easyEvolutions)

		// Zusatz-Schalter
		// Attacken beim Levelaufstieg: 0 unverändert, 1 zufällig (gleicher Typ bevorzugt), 2 komplett zufällig, 3 nur Metronom
		setMovesetsMod(*pick(4, if (randomMoves) 1 else 0))
		setStartWithGuaranteedMoves(randomMoves)
		setGuaranteedMoveCount(2)
		setMovesetsForceGoodDamaging(randomMoves)
		setMovesetsGoodDamagingPercent(50)
		// TMs: 0 unverändert, 1 zufällig
		setTmsMod(*pick(2, if (randomTms) 1 else 0))
		setTmsForceGoodDamaging(randomTms)
		setTmsGoodDamagingPercent(50)
		// Items auf dem Boden: 0 unverändert, 1 mischen, 2 zufällig, 3 zufällig gleichmäßig
		setFieldItemsMod(*pick(4, if (shuffleFieldItems) 1 else 0))
		// Fähigkeiten: 0 unverändert, 1 zufällig
		setAbilitiesMod(*pick(2, if (randomAbilities) 1 else 0))
		setBanNegativeAbilities(randomAbilities)
		setAbilitiesFollowEvolutions(randomAbilities)
		// Typen: 0 unverändert, 1 zufällig (Entwicklungen folgen), 2 komplett zufällig
		setTypesMod(*pick(3, if (randomTypes) 1 else 0))
		// Basiswerte: 0 unverändert, 1 mischen, 2 zufällig
		setBaseStatisticsMod(*pick(3, if (shuffleBaseStats) 1 else 0))
		setBaseStatsFollowEvolutions(shuffleBaseStats)

		// Eingebaute Zusatz-Patches des Randomizers
		var tweaks = MiscTweak.NO_MISC_TWEAKS
		if (fastText) tweaks = tweaks or MiscTweak.FASTEST_TEXT.value
		if (nationalDex) tweaks = tweaks or MiscTweak.NATIONAL_DEX_AT_START.value
		if (fastBars) tweaks = tweaks or MiscTweak.FASTER_HP_AND_EXP_BARS.value
		setCurrentMiscTweaks(tweaks)
	}
}
