# FossFiles

Administrador de archivos Android nativo en **un solo lenguaje: Kotlin** (lógica + UI con Jetpack Compose,
que compila a bytecode nativo del sistema, sin puente a otro runtime). La idea: algo parecido al Files de
Google, completo, pero **sin telemetría, sin servicios de Google (GMS), ligero y rápido**.

## Políticas del proyecto

### Cero Internet y cero telemetría (garantía verificable, no una promesa)
- El `AndroidManifest.xml` **no declara `android.permission.INTERNET`**. Sin ese permiso, Android bloquea
  a nivel de sistema operativo cualquier conexión de red, así que la app no podría enviar datos a ningún
  servidor aunque el código lo intentara.
- Cero SDKs de terceros: nada de Firebase, Crashlytics, Analytics, Ads, ni Google Play Services.
  Solo AndroidX, Compose y kotlinx-coroutines. (JUnit se usa únicamente para las pruebas y no entra en el APK.)
- Para comprobarlo en cualquier APK: `aapt2 dump permissions app-release.apk` no debe listar `INTERNET`.
- Todo el código está en este repo: puedes leerlo entero en un rato.

### Cero animaciones (para que se sienta —y sea— rápida)
- La app no anima nada por sí misma: sin transiciones entre pantallas, sin ripple al tocar, sin hojas ni
  menús que se deslizan, sin barras de progreso indeterminadas (que se animan sin parar y gastan batería).
- Los diálogos son una capa más de la pantalla (no ventanas del sistema), así aparecen al instante.
- Los avisos y errores salen en una franja fija en la parte baja (se cierra tocándola), no en un Toast.
- Se apaga el efecto de "estirar" al llegar al final de una lista y las animaciones de ventana del tema.
- Limitaciones que son del sistema y no se pueden quitar desde la app: la pantalla de arranque (splash) de
  Android 12+ y las animaciones de teclado/gestos del propio Android.

## Funciones incluidas
- Navegar carpetas, subir de nivel, buscar por nombre (hasta 6 niveles; la búsqueda anterior se cancela al seguir escribiendo)
- Ordenar por nombre, fecha, tamaño o tipo (carpetas siempre primero)
- **Selección múltiple** (mantén pulsado): copiar, cortar, renombrar (de a uno), eliminar y seleccionar todo
- Crear carpeta, pegar en la carpeta actual
- Copiar sin pisar nada: si el nombre existe, crea «nombre (1)», «nombre (2)»…
- Mover = renombrado instantáneo en el mismo volumen (solo copia y borra si cambia de volumen)
- Confirmación antes de eliminar
- Categorías Fotos, Videos, Audio, Descargas y Documentos (vía MediaStore, sin recorrer el disco)
- Íconos por tipo de archivo y tamaños legibles (KB/MB/GB)
- Botón Atrás: cierra la selección, sube de carpeta o vuelve al inicio

## Cómo compilarlo
1. Abre la carpeta `fossfiles/` en Android Studio (Hedgehog o más reciente).
2. Deja que Gradle sincronice (descargará Kotlin/Compose/AndroidX — estas SÍ requieren
   internet una sola vez, en tu máquina de desarrollo, para compilar la app; el APK
   resultante no la necesita).
3. Conecta un dispositivo o usa un emulador con Android 8.0 (API 26) o superior.
4. Run ▶.
5. La primera vez, la app te pedirá el permiso "Acceso a todos los archivos" —
   te lleva directo a Ajustes para activarlo.

## Pruebas
La lógica de archivos (`FileRepository`) no depende de Android y tiene pruebas de JVM:

    ./gradlew testDebugUnitTest

Cubren: nombres inválidos, copiar/pegar sin pisar, mover a la misma carpeta, carpeta dentro de sí misma,
borrar sin seguir enlaces simbólicos, profundidad y cancelación de la búsqueda, y orden del listado.

## Notas de comportamiento
- Los enlaces simbólicos no se siguen: al copiar/mover se omiten y al borrar se elimina solo el enlace.
- La búsqueda muestra como máximo 5.000 resultados y avisa si hubo más.
- Cortar y pegar entre volúmenes (p. ej. a una tarjeta SD) copia y luego borra; si no se pudo borrar el
  original, la app te lo dice en vez de fallar en silencio.

## Posibles siguientes pasos
- Miniaturas de imágenes/video (con la API de plataforma, sin librerías de terceros)
- Barra de accesos rápidos a Descargas, DCIM, etc. y sección de recientes
- Mostrar/ocultar archivos ocultos, vista en cuadrícula, comprimir/descomprimir ZIP
- Compartir con otras apps y detalles/propiedades de un archivo
- Firmar el APK release con tu propio keystore para publicarlo (hoy usa la clave de debug)
