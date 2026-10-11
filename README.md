# FossFiles

File manager Android nativo en **un solo lenguaje: Kotlin** (lógica + UI con Jetpack Compose,
que compila a bytecode nativo del sistema, sin puente a otro runtime).

## Por qué no hay telemetría (garantía verificable, no una promesa)
- El `AndroidManifest.xml` **no declara `android.permission.INTERNET`**. Sin ese permiso,
  Android bloquea a nivel de sistema operativo cualquier conexión de red, así que la app
  no podría enviar datos a ningún servidor aunque el código lo intentara.
- Cero dependencias de terceros: nada de Firebase, Crashlytics, Analytics, Ads, ni SDKs
  de publicidad. Solo AndroidX y Compose, que son parte del framework oficial de Android.
- Todo el código está en este repo: puedes leerlo entero en un rato.

## Funciones incluidas
- Navegar carpetas, subir de nivel, buscar por nombre (hasta 6 niveles de profundidad)
- Ordenar por nombre, fecha, tamaño o tipo (carpetas siempre primero)
- Crear carpeta, renombrar, eliminar, copiar/cortar y pegar
- Tamaños de archivo legibles (KB/MB/GB)

## Cómo compilarlo
1. Abre la carpeta `fossfiles/` en Android Studio (Hedgehog o más reciente).
2. Deja que Gradle sincronice (descargará Kotlin/Compose/AndroidX — estas SÍ requieren
   internet una sola vez, en tu máquina de desarrollo, para compilar la app; el APK
   resultante no la necesita).
3. Conecta un dispositivo o usa un emulador con Android 8.0 (API 26) o superior.
4. Run ▶.
5. La primera vez, la app te pedirá el permiso "Acceso a todos los archivos" —
   te lleva directo a Ajustes para activarlo.

## Posibles siguientes pasos
- Miniaturas de imágenes/video
- Barra de accesos rápidos a Descargas, DCIM, etc.
- Selección múltiple para operaciones en lote
- Firmar el APK release para instalarlo sin Android Studio
