package com.fossfiles.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.fossfiles.app.ui.CategoryListScreen
import com.fossfiles.app.ui.FileListScreen
import com.fossfiles.app.ui.FossFilesTheme
import com.fossfiles.app.ui.HomeScreen
import com.fossfiles.app.ui.ImageViewer
import com.fossfiles.app.ui.MediaPlayerOverlay
import com.fossfiles.app.ui.MessageBanner
import com.fossfiles.app.ui.PlainButton
import com.fossfiles.app.ui.PlainTextButton
import com.fossfiles.app.ui.ProgressStrip
import com.fossfiles.app.ui.SettingsScreen
import com.fossfiles.app.ui.TrashScreen
import com.fossfiles.app.ui.ZipPromptDialog
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: FileViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                @Suppress("UNCHECKED_CAST")
                // applicationContext, NUNCA "this" (la Activity): así el
                // ViewModel no retiene la Activity y no hay riesgo de fuga.
                return FileViewModel(Environment.getExternalStorageDirectory(), applicationContext) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Registro LOCAL del último error inesperado (no se envía a ningún lado: no hay Internet).
        CrashLog.install(this)
        setContent {
            FossFilesTheme(viewModel.themeMode.value) {
                var hasPermission by remember { mutableStateOf(hasStoragePermission()) }
                val message by viewModel.message

                // Atrás: primero cierra la selección, luego sube de carpeta / vuelve al Home.
                // En el Home (sin selección) queda desactivado y Android cierra la app.
                BackHandler(
                    enabled = hasPermission &&
                        (viewModel.selection.value.isNotEmpty() || viewModel.screenMode.value != ScreenMode.HOME)
                ) {
                    handleBack()
                }

                Box(Modifier.fillMaxSize()) {
                    if (hasPermission) {
                        when (viewModel.screenMode.value) {
                            ScreenMode.HOME -> HomeScreen(viewModel = viewModel)
                            ScreenMode.BROWSE -> FileListScreen(viewModel = viewModel, onBack = { viewModel.goHome() })
                            ScreenMode.CATEGORY -> CategoryListScreen(viewModel = viewModel, onBack = { viewModel.goHome() })
                            ScreenMode.TRASH -> TrashScreen(viewModel = viewModel, onBack = { viewModel.goHome() })
                            ScreenMode.SETTINGS -> SettingsScreen(viewModel = viewModel, onBack = { viewModel.goHome() })
                        }
                    } else {
                        PermissionScreen(
                            onGrant = { requestStoragePermission() },
                            onOpenSettings = { openAppSettings() }
                        )
                    }

                    // Al tocar un ZIP: ¿extraerlo o abrirlo con otra app?
                    viewModel.zipPrompt.value?.let { item ->
                        ZipPromptDialog(
                            name = item.name,
                            onExtract = { viewModel.extractZip(item) },
                            onOpenWith = {
                                viewModel.zipPrompt.value = null
                                openFileExternally(this@MainActivity, item.file)?.let { viewModel.message.value = it }
                            },
                            onDismiss = { viewModel.zipPrompt.value = null }
                        )
                    }

                    // Visor de imágenes: va encima de todo (su botón Atrás se registra después, así que gana).
                    viewModel.viewer.value?.let { state ->
                        ImageViewer(
                            state = state,
                            onStep = { viewModel.viewerStep(it) },
                            onOpenWith = { item ->
                                openFileExternally(this@MainActivity, item.file)?.let { viewModel.message.value = it }
                            },
                            onClose = { viewModel.closeViewer() }
                        )
                    }

                    // Reproductor de audio/video, también encima de todo.
                    viewModel.media.value?.let { state ->
                        MediaPlayerOverlay(
                            state = state,
                            controller = viewModel.player,
                            onStep = { viewModel.mediaStep(it) },
                            onOpenWith = { item ->
                                viewModel.closeMedia()
                                openFileExternally(this@MainActivity, item.file)?.let { viewModel.message.value = it }
                            },
                            onClose = { viewModel.closeMedia() }
                        )
                    }

                    // Abajo: progreso de la operación en curso (con «Cancelar») y avisos. Sin animaciones
                    // (un Toast del sistema se anima al aparecer y desaparecer). El aviso se quita solo a los 6 s.
                    Column(Modifier.align(Alignment.BottomCenter)) {
                        viewModel.progressInfo.value?.let { info ->
                            ProgressStrip(info = info, onCancel = { viewModel.cancelWork() })
                        }
                        message?.let { text ->
                            LaunchedEffect(text) {
                                delay(6000)
                                viewModel.dismissMessage()
                            }
                            MessageBanner(text = text, onDismiss = { viewModel.dismissMessage() })
                        }
                    }
                }

                // Vuelve a comprobar el permiso cuando la app regresa a primer plano
                // (por ejemplo, tras volver de la pantalla de Ajustes).
                DisposableLifecycleCheck {
                    hasPermission = hasStoragePermission()
                    // Al volver a la app (p. ej. tras conceder el permiso, insertar una tarjeta SD o descargar
                    // algo) se actualizan volúmenes y recientes del Home.
                    if (hasPermission && viewModel.screenMode.value == ScreenMode.HOME) viewModel.refreshHome()
                }
            }
        }
    }

    // Al salir de la app (o apagar la pantalla) el audio/video se pausa: no hay servicio en segundo plano.
    override fun onStop() {
        super.onStop()
        viewModel.pausePlayback()
    }

    // Con poca memoria se sueltan las miniaturas (se vuelven a generar al hacer falta).
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL_LEVEL) ThumbnailLoader.clear()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ThumbnailLoader.clear()
    }

    private fun handleBack() {
        if (viewModel.selection.value.isNotEmpty()) {
            viewModel.clearSelection()
        } else if (viewModel.screenMode.value == ScreenMode.TRASH && viewModel.trashSelection.value.isNotEmpty()) {
            viewModel.clearTrashSelection()
        } else if (viewModel.screenMode.value == ScreenMode.BROWSE) {
            if (!viewModel.goUp()) viewModel.goHome()
        } else {
            viewModel.goHome()
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // Algunos fabricantes no incluyen esa pantalla: se prueba la lista general y, si tampoco, los ajustes de la app.
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: ActivityNotFoundException) {
                    openAppSettings()
                }
            }
        } else {
            val prefs = getSharedPreferences("fossfiles", MODE_PRIVATE)
            val askedBefore = prefs.getBoolean("askedStorage", false)
            if (askedBefore && !shouldShowRequestPermissionRationale(android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
                // El usuario marcó "No volver a preguntar": Android ya no muestra el cuadro, solo se activa a mano.
                openAppSettings()
            } else {
                prefs.edit().putBoolean("askedStorage", true).apply()
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    1001
                )
            }
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            // sin pantalla de ajustes disponible: no hay nada más que ofrecer
        }
    }

    private companion object {
        // ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL (15): la constante está marcada como obsoleta, el valor no cambia.
        const val TRIM_MEMORY_RUNNING_CRITICAL_LEVEL = 15
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "FossFiles necesita acceso al almacenamiento para mostrar tus archivos.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "La app no tiene permiso de Internet, así que nunca puede enviar tus datos a ningún servidor.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        PlainButton(text = "Conceder acceso", onClick = onGrant)
        Spacer(Modifier.height(8.dp))
        PlainTextButton("Abrir ajustes de la app", onOpenSettings)
    }
}

@Composable
private fun DisposableLifecycleCheck(onResume: () -> Unit) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
