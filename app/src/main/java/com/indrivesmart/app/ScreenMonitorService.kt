package com.indrivesmart.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.regex.Pattern

class ScreenMonitorService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "com.indrivesmart.START"
        const val ACTION_STOP = "com.indrivesmart.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "indrive_live_analysis"
        private const val NOTIFICATION_ID = 303
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: Handler? = null
    private var thread: HandlerThread? = null
    private var overlay: TextView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpoken = ""
    private var lastProcessedAt = 0L
    private var processing = false
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            startForeground(NOTIFICATION_ID, buildNotification())
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                       else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
            if (data != null) beginCapture(resultCode, data)
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        stopCapture()
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        thread = HandlerThread("InDriveScreenOCR").also { it.start() }
        worker = Handler(thread!!.looper)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            if (processing || now - lastProcessedAt < 1800) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            processing = true
            lastProcessedAt = now
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmapWithPadding = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bitmapWithPadding.copyPixelsFromBuffer(buffer)
                val bitmap = Bitmap.createBitmap(bitmapWithPadding, 0, 0, width, height)
                bitmapWithPadding.recycle()
                image.close()
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result -> analyzeText(result.text) }
                    .addOnFailureListener { show("No se pudo leer la oferta. Mantén visible la pantalla.", false) }
                    .addOnCompleteListener {
                        bitmap.recycle()
                        processing = false
                    }
            } catch (_: Exception) {
                image.close()
                processing = false
            }
        }, worker)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
                stopSelf()
            }
        }, worker)
        virtualDisplay = projection?.createVirtualDisplay(
            "InDriveAssistantCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, worker
        )
        show("Analizando pantalla… abre inDrive y espera una oferta.", false)
    }

    private fun analyzeText(text: String) {
        val price = findValue(text, "(?i)S\\s*/\\.?\\s*([0-9]{1,3}(?:[.,][0-9]{1,2})?)")
        val km = findValue(text, "(?i)([0-9]+(?:[.,][0-9]+)?)\\s*km\\b")
        val minutes = findValue(text, "(?i)([0-9]+)\\s*(?:min|minutos)\\b")
        if (price == null || km == null) {
            if (text.contains("S/", true) || text.contains("km", true)) {
                show("Oferta detectada, pero no pude reconocer precio y distancia con seguridad. Verifica manualmente.", false)
            }
            return
        }
        val prefs = getSharedPreferences("driver", MODE_PRIVATE)
        val fuelPrice = prefs.getString("glp", "2.00")?.toDoubleOrNull() ?: 2.0
        val efficiency = prefs.getString("eff", "9")?.toDoubleOrNull() ?: 9.0
        val fuelCost = km / efficiency * fuelPrice
        val net = price - fuelCost
        val perKm = net / km
        val hourly = if (minutes != null && minutes > 0) net / (minutes / 60.0) else null

        // Conservative heuristic; this is an estimate from visible text, not a guaranteed fare.
        val good = perKm >= 1.4 && (hourly == null || hourly >= 18.0)
        val poor = perKm < 0.8 || (hourly != null && hourly < 12.0)
        val verdict = when {
            good -> "CONVIENE"
            poor -> "NO CONVIENE"
            else -> "REVISAR / NEGOCIAR"
        }
        val timeText = minutes?.let { " · ${it.toInt()} min" } ?: " · tiempo no detectado"
        val details = "S/ %.2f · %.1f km%s\nNeto aprox. combustible: S/ %.2f · S/ %.2f/km".format(
            Locale("es", "PE"), price, km, timeText, net, perKm
        )
        show("$verdict\n$details", true)
    }

    private fun findValue(text: String, regex: String): Double? {
        val matcher = Pattern.compile(regex).matcher(text)
        if (!matcher.find()) return null
        return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
    }

    private fun show(message: String, speak: Boolean) {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (overlay == null) {
            overlay = TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(24, 18, 24, 18)
                setBackgroundColor(0xEE17283D.toInt())
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 90
            }
            try { wm.addView(overlay, params) } catch (_: Exception) { overlay = null }
        }
        overlay?.text = message
        if (speak && message != lastSpoken) {
            lastSpoken = message
            if (ttsReady) tts?.speak(message.replace("\n", ". "), TextToSpeech.QUEUE_FLUSH, null, "fare_result")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale("es", "PE")
        }
    }

    private fun stopCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try {
            overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (_: Exception) {}
        overlay = null
        thread?.quitSafely()
        thread = null
        worker = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Análisis en vivo", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("InDrive Assistant está analizando")
            .setContentText("Captura de pantalla activa. Toca DETENER en la app para finalizar.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        stopCapture()
        tts?.stop()
        tts?.shutdown()
        try { recognizer.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
