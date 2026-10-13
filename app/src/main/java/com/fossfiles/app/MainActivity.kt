package com.fossfiles.app

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
import com.fossfiles.app.ui.MessageBanner
import com.fossfiles.app.ui.PlainButton
import java.io.File

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
        setContent {
            FossFilesTheme {
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
                        }
                    } else {
                        PermissionScreen(onGrant = { requestStoragePermission() })
                    }

                    // Aviso dentro de la app (un Toast del sistema se anima al aparecer y desaparecer).
                    message?.let { text ->
                        MessageBanner(
                            text = text,
                            onDismiss = { viewModel.dismissMessage() },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }

                // Vuelve a comprobar el permiso cuando la app regresa a primer plano
                // (por ejemplo, tras volver de la pantalla de Ajustes).
                DisposableLifecycleCheck {
                    hasPermission = hasStoragePermission()
                    // Al volver a la app (p. ej. tras conceder el permiso o descargar algo) se
                    // actualizan los recientes y el almacenamiento del Home.
                    if (hasPermission && viewModel.screenMode.value == ScreenMode.HOME) viewModel.refreshHome()
                }
            }
        }
    }

    private fun handleBack() {
        if (viewModel.selection.value.isNotEmpty()) {
            viewModel.clearSelection()
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
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
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

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
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
