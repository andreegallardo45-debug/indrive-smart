# InDrive Assistant 3.0 (Android)

Aplicación de apoyo que analiza la pantalla con MediaProjection + ML Kit OCR y muestra una recomendación en una ventana flotante con aviso por voz. No pulsa botones ni acepta/rechaza viajes.

## Permisos y uso
1. Instalar el APK.
2. Abrir la app y configurar precio del GLP y rendimiento real (km/l).
3. Pulsar **INICIAR ANÁLISIS EN VIVO**.
4. Autorizar la ventana flotante si Android lo solicita.
5. Autorizar la captura de pantalla en el diálogo del sistema Android.
6. Cambiar a inDrive. La app procesa fotogramas localmente en el dispositivo.
7. Para finalizar, volver a InDrive Assistant y pulsar **DETENER ANÁLISIS**.

Android muestra una notificación persistente mientras la captura está activa. El usuario debe iniciar y autorizar cada sesión. No hay captura oculta ni aceptación automática.

## Limitaciones importantes
- El OCR depende del diseño, idioma y nitidez de la pantalla de inDrive. Debes comprobar los valores detectados.
- El análisis usa solo precio, kilómetros y minutos que logre leer. Si no detecta minutos, no calcula rentabilidad por hora.
- El costo mostrado descuenta combustible estimado, no todos los gastos (mantenimiento, depreciación, peajes, tiempo de espera ni retorno vacío).
- Los umbrales de recomendación son heurísticos de demostración y deben calibrarse con viajes reales; no garantizan ganancias.
- El uso de capturas/ventanas superpuestas puede estar sujeto a las condiciones de inDrive y a las políticas de Android. No se automatiza ninguna acción dentro de inDrive.

## Compilar
Requiere JDK 17, Android SDK 35 y Gradle 8.9:
`gradle assembleDebug`
APK: `app/build/outputs/apk/debug/app-debug.apk`
