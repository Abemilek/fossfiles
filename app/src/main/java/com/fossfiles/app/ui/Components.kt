package com.fossfiles.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

// Todo lo de este archivo existe para evitar los componentes de Material que animan
// (ripple, elevación al pulsar, hojas y menús deslizantes, campos con etiqueta flotante).

/** Clic / clic largo sin ripple ni ninguna animación. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.plainClickable(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onLongClick = onLongClick,
        onClick = onClick
    )
}

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp).plainClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
fun PlainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
            .plainClickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun PlainTextButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.plainClickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

@Composable
fun PlainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    onClear: (() -> Unit)? = null,
    onDone: () -> Unit = {}
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                    inner()
                }
                if (onClear != null && value.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Borrar búsqueda",
                        modifier = Modifier.size(20.dp).plainClickable(onClick = onClear)
                    )
                }
            }
        }
    )
}

/**
 * Diálogo dibujado como una capa más de la pantalla (no como una ventana del sistema),
 * así aparece y desaparece al instante, sin el fundido de los diálogos de Android.
 */
@Composable
fun ModalOverlay(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .plainClickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .plainClickable {} // consume los toques para que no cierren la capa
        ) {
            Column(Modifier.padding(24.dp), content = content)
        }
    }
}

@Composable
fun NameInputDialog(title: String, initialValue: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialValue) }
    ModalOverlay(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(16.dp))
        PlainTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = "Nombre",
            onDone = { if (text.isNotBlank()) onConfirm(text) }
        )
        Spacer(Modifier.size(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PlainTextButton("Cancelar", onDismiss)
            PlainTextButton("Aceptar") { if (text.isNotBlank()) onConfirm(text) }
        }
    }
}

@Composable
fun ConfirmDeleteDialog(names: List<String>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val what = if (names.size == 1) "«${names.first()}»" else "${names.size} elementos"
    ModalOverlay(onDismiss = onDismiss) {
        Text("¿Eliminar $what?", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(12.dp))
        Text(
            "Se borrará de forma permanente. Si es una carpeta, también todo lo que contiene. Esta acción no se puede deshacer.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.size(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PlainTextButton("Cancelar", onDismiss)
            PlainTextButton("Eliminar", onConfirm)
        }
    }
}

/** Aviso en la parte baja de la pantalla; se cierra tocándolo. Sin animación de entrada/salida. */
@Composable
fun MessageBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(12.dp))
            .plainClickable(onClick = onDismiss)
            .padding(16.dp)
    ) {
        Text(text, color = MaterialTheme.colorScheme.inverseOnSurface)
    }
}
