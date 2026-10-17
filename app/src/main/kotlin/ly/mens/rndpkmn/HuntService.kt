package ly.mens.rndpkmn

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

// Hintergrunddienst der Shiny-Jagd.
// Nimmt den Hauptbildschirm in winziger Auflösung auf (ca. 160 x 90 Pixel, 4 Bilder pro Sekunde)
// und zählt jeden Kampf. Das kostet kaum Rechenleistung.
class HuntService : Service() {

	companion object {
		const val CHANNEL = "SHINY_HUNT"
		const val NOTIF_ID = 4711
		const val ACTION_START = "ly.mens.rndpkmn.hunt.START"
		const val ACTION_CALIBRATE = "ly.mens.rndpkmn.hunt.CALIBRATE"
		const val ACTION_MINUS = "ly.mens.rndpkmn.hunt.MINUS"
		const val ACTION_STOP = "ly.mens.rndpkmn.hunt.STOP"
		const val EXTRA_CODE = "code"
		const val EXTRA_DATA = "data"
		const val EXTRA_DELAY = "delay"
		private const val WIDTH = 160
		private const val INTERVAL_MS = 250L

		fun startIntent(ctx: Context, code: Int, data: Intent): Intent =
			Intent(ctx, HuntService::class.java).setAction(ACTION_START)
				.putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)

		fun action(ctx: Context, action: String, delayMs: Long = 0): Intent =
			Intent(ctx, HuntService::class.java).setAction(action).putExtra(EXTRA_DELAY, delayMs)
	}

	private lateinit var thread: HandlerThread
	private lateinit var handler: Handler
	private var projection: MediaProjection? = null
	private var display: VirtualDisplay? = null
	private var reader: ImageReader? = null
	private var detector: BattleDetector? = null
	private var frame = IntArray(0)
	private var w = 0
	private var h = 0

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		HuntState.load(this)
		thread = HandlerThread("ShinyHunt").also { it.start() }
		handler = Handler(thread.looper)
		val nm = getSystemService(NotificationManager::class.java)!!
		nm.createNotificationChannel(
			NotificationChannel(CHANNEL, "Shiny-Jagd", NotificationManager.IMPORTANCE_LOW).apply {
				description = "Zähler der laufenden Shiny-Jagd"
			}
		)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val i = intent ?: return START_NOT_STICKY
		when (i.action) {
			ACTION_START -> {
				val note = buildNotification()
				startForeground(NOTIF_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
				val code = i.getIntExtra(EXTRA_CODE, 0)
				val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
				} else {
					@Suppress("DEPRECATION")
					i.getParcelableExtra(EXTRA_DATA)
				}
				if (code != Activity.RESULT_OK || data == null || !startCapture(code, data)) {
					setStatus("Bildschirmaufnahme nicht erlaubt.")
					stopSelf()
				}
			}
			ACTION_CALIBRATE -> {
				val d = detector
				if (d == null) {
					setStatus("Erst die Jagd starten.")
				} else {
					val delay = i.getLongExtra(EXTRA_DELAY, 3000L)
					setStatus("Kampf wird in ${delay / 1000} s gemerkt. Leiste schließen!")
					handler.postDelayed({ d.requestCalibrate() }, delay)
				}
			}
			ACTION_MINUS -> {
				HuntState.post { HuntState.add(this, -1); updateNotification() }
			}
			ACTION_STOP -> stopSelf()
		}
		return START_NOT_STICKY
	}

	private fun startCapture(code: Int, data: Intent): Boolean {
		return try {
			val mpm = getSystemService(MediaProjectionManager::class.java)!!
			val p = mpm.getMediaProjection(code, data) ?: return false
			projection = p
			p.registerCallback(object : MediaProjection.Callback() {
				override fun onStop() { HuntState.post { stopSelf() } }
			}, handler)

			val dm = resources.displayMetrics
			val big = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
			val small = minOf(dm.widthPixels, dm.heightPixels).toFloat()
			// Querformat (Thor oben); Seitenverhältnis wie der echte Bildschirm
			w = WIDTH
			h = (WIDTH * small / big).roundToInt().coerceIn(40, 200)
			frame = IntArray(w * h)
			val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
			reader = r
			display = p.createVirtualDisplay(
				"ShinyHunt", w, h, 120,
				DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
				r.surface, null, handler
			)
			val d = BattleDetector(w, h)
			d.enterThreshold = HuntState.sensitivity / 100f
			loadReference(d)
			detector = d
			HuntState.post {
				HuntState.running = true
				HuntState.calibrated = d.ref != null
				HuntState.status = if (d.ref != null) "Läuft. Gemerkter Kampf wird benutzt."
				else "Läuft. Im ersten Kampf: Leiste runter, \"Kampf merken\"."
				updateNotification()
			}
			handler.postDelayed(tick, INTERVAL_MS)
			true
		} catch (e: Exception) {
			setStatus("Fehler: ${e.message}")
			false
		}
	}

	private val tick = object : Runnable {
		override fun run() {
			val d = detector ?: return
			val r = reader ?: return
			try {
				val img = r.acquireLatestImage()
				if (img != null) {
					val plane = img.planes[0]
					readPixels(plane.buffer, plane.rowStride, plane.pixelStride)
					img.close()
					d.enterThreshold = HuntState.sensitivity / 100f
					val ev = d.onFrame(frame)
					val sim = (d.lastSimilarity * 100).roundToInt()
					val battle = d.inBattle
					HuntState.post {
						HuntState.similarity = sim
						HuntState.inBattle = battle
					}
					handleEvent(ev, d)
				}
			} catch (e: Exception) {
				// einzelnes Bild verloren, egal
			}
			handler.postDelayed(this, INTERVAL_MS)
		}
	}

	private fun readPixels(buf: ByteBuffer, rowStride: Int, pixelStride: Int) {
		for (y in 0 until h) {
			val row = y * rowStride
			for (x in 0 until w) {
				val i = row + x * pixelStride
				val rr = buf.get(i).toInt() and 0xFF
				val gg = buf.get(i + 1).toInt() and 0xFF
				val bb = buf.get(i + 2).toInt() and 0xFF
				frame[y * w + x] = (rr shl 16) or (gg shl 8) or bb
			}
		}
	}

	private fun handleEvent(ev: BattleDetector.Event, d: BattleDetector) {
		when (ev) {
			BattleDetector.Event.CALIBRATED -> {
				saveReference(d)
				HuntState.post {
					HuntState.calibrated = true
					HuntState.add(this, 1)
					HuntState.status = "Kampf gemerkt. Ab jetzt wird automatisch gezählt."
					updateNotification()
				}
			}
			BattleDetector.Event.RECALIBRATED -> {
				saveReference(d)
				setStatus("Kampf neu gemerkt.")
			}
			BattleDetector.Event.CALIBRATE_FAILED ->
				setStatus("Merken fehlgeschlagen (Bild zu dunkel?). Nochmal im Kampf versuchen.")
			BattleDetector.Event.ENCOUNTER -> HuntState.post {
				HuntState.add(this, 1)
				HuntState.status = "Begegnung gezählt."
				updateNotification()
			}
			BattleDetector.Event.BATTLE_END -> HuntState.post {
				HuntState.status = "Warte auf nächsten Kampf."
			}
			BattleDetector.Event.NONE -> {}
		}
	}

	// Referenzbild sichern, damit man nach einem Neustart nicht neu merken muss
	private fun refFile() = File(noBackupFilesDir, "hunt_ref_${w}x$h.bin")

	private fun saveReference(d: BattleDetector) {
		val ref = d.ref ?: return
		try {
			val bb = ByteBuffer.allocate(ref.size * 4)
			bb.asIntBuffer().put(ref)
			refFile().writeBytes(bb.array())
		} catch (e: Exception) { }
	}

	private fun loadReference(d: BattleDetector) {
		try {
			val f = refFile()
			if (!f.exists()) return
			val bytes = f.readBytes()
			if (bytes.size != w * h * 4) return
			val arr = IntArray(w * h)
			ByteBuffer.wrap(bytes).asIntBuffer().get(arr)
			d.setReference(arr)
		} catch (e: Exception) { }
	}

	private fun setStatus(text: String) {
		HuntState.post {
			HuntState.status = text
			updateNotification()
		}
	}

	private fun pending(act: String, req: Int): PendingIntent =
		PendingIntent.getService(this, req, action(this, act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

	private fun buildNotification(): Notification {
		val open = PendingIntent.getActivity(
			this, 0, Intent(this, MainActivity::class.java),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)
		val title = (HuntState.target.ifBlank { "Shiny-Jagd" }) + ": " + HuntState.count
		val text = "Chance bisher ${HuntState.chanceText()} · 1/${HuntState.odds}"
		return NotificationCompat.Builder(this, CHANNEL)
			.setSmallIcon(R.drawable.ic_overwrite_last)
			.setContentTitle(title)
			.setContentText(text)
			.setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n" + HuntState.status))
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setContentIntent(open)
			.addAction(0, "Kampf merken", pending(ACTION_CALIBRATE, 1))
			.addAction(0, "−1", pending(ACTION_MINUS, 2))
			.addAction(0, "Stopp", pending(ACTION_STOP, 3))
			.build()
	}

	private fun updateNotification() {
		if (!HuntState.running) return
		getSystemService(NotificationManager::class.java)!!.notify(NOTIF_ID, buildNotification())
	}

	override fun onDestroy() {
		handler.removeCallbacksAndMessages(null)
		try { display?.release() } catch (e: Exception) { }
		try { reader?.close() } catch (e: Exception) { }
		try { projection?.stop() } catch (e: Exception) { }
		display = null; reader = null; projection = null; detector = null
		thread.quitSafely()
		HuntState.post {
			HuntState.running = false
			HuntState.inBattle = false
			HuntState.status = "Gestoppt. Zähler bleibt gespeichert."
		}
		super.onDestroy()
	}
}
