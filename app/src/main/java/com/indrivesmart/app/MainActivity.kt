package com.indrivesmart.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var glp: EditText
    private lateinit var efficiency: EditText
    private val projectionRequest = 7301
    private val overlayRequest = 7302
    private val notificationRequest = 7303

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("driver", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 40, 36, 30)
            setBackgroundColor(0xFF101827.toInt())
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFFEAF0FA.toInt())
            setPadding(0, 18, 0, 6)
        }
        val title = label("🚗 InDrive Assistant 3.0").apply {
            textSize = 25f
            setPadding(0, 8, 0, 8)
        }
        root.addView(title)
        root.addView(label("Analiza las ofertas visibles en pantalla y te avisa por voz. No acepta carreras."))
        root.addView(label("Precio del GLP (S/ por litro)"))
        glp = EditText(this).apply {
            inputType = 8194
            setText(prefs.getString("glp", "2.00"))
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9AAAC0.toInt())
            hint = "Ej. 2.00"
        }
        root.addView(glp, ViewGroup.LayoutParams(-1, -2))
        root.addView(label("Rendimiento real con GLP (km/litro)"))
        efficiency = EditText(this).apply {
            inputType = 8194
            setText(prefs.getString("eff", "9"))
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9AAAC0.toInt())
            hint = "Ej. 9"
        }
        root.addView(efficiency, ViewGroup.LayoutParams(-1, -2))
        val start = Button(this).apply {
            text = "INICIAR ANÁLISIS EN VIVO"
            setOnClickListener { saveAndStart() }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 28 })
        val stop = Button(this).apply {
            text = "DETENER ANÁLISIS"
            setOnClickListener {
                startService(Intent(this@MainActivity, ScreenMonitorService::class.java).setAction(ScreenMonitorService.ACTION_STOP))
                Toast.makeText(this@MainActivity, "Análisis detenido", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 })
        root.addView(label("Cómo funciona"))
        root.addView(label("1. Configura GLP y rendimiento una sola vez.\n2. Pulsa iniciar y autoriza la captura de pantalla de Android.\n3. Cambia a inDrive. El asistente analiza periódicamente lo que aparece en pantalla.\n4. La ventana flotante y la voz muestran la recomendación."))
        root.addView(TextView(this).apply {
            text = "Privacidad: el análisis se realiza en el teléfono. La captura puede mostrar información personal; detén el servicio cuando no quieras analizar la pantalla."
            textSize = 13f
            setTextColor(0xFF9AAAC0.toInt())
            setPadding(0, 20, 0, 0)
        })
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
        }
    }

    private fun saveAndStart() {
        val fuel = glp.text.toString().toDoubleOrNull()
        val kmPerLitre = efficiency.text.toString().toDoubleOrNull()
        if (fuel == null || fuel <= 0 || kmPerLitre == null || kmPerLitre <= 0) {
            Toast.makeText(this, "Ingresa valores válidos de GLP y rendimiento", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("driver", MODE_PRIVATE).edit()
            .putString("glp", fuel.toString())
            .putString("eff", kmPerLitre.toString()).apply()

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, overlayRequest)
            return
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
    }

    @Deprecated("Deprecated Android callback kept for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == overlayRequest) {
            if (Settings.canDrawOverlays(this)) requestScreenCapture()
            else Toast.makeText(this, "Debes permitir la ventana flotante para usar el asistente", Toast.LENGTH_LONG).show()
        } else if (requestCode == projectionRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val service = Intent(this, ScreenMonitorService::class.java).apply {
                    action = ScreenMonitorService.ACTION_START
                    putExtra(ScreenMonitorService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenMonitorService.EXTRA_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                Toast.makeText(this, "Análisis en vivo iniciado. Ahora abre inDrive.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "No autorizaste la captura de pantalla", Toast.LENGTH_LONG).show()
            }
        }
    }
}
