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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fossfiles.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.PluralsRes
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.fossfiles.app.ProgressInfo

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

/** Marco visual común de los campos de texto (fondo redondeado, ícono, texto de ayuda y borrar). */
@Composable
private fun FieldFrame(
    isEmpty: Boolean,
    placeholder: String,
    textStyle: TextStyle,
    leadingIcon: ImageVector?,
    onClear: (() -> Unit)?,
    inner: @Composable () -> Unit
) {
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
            if (isEmpty) {
                Text(placeholder, style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
            }
            inner()
        }
        if (onClear != null && !isEmpty) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.clear_search),
                modifier = Modifier.size(20.dp).plainClickable(onClick = onClear)
            )
        }
    }
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
        decorationBox = { inner -> FieldFrame(value.isEmpty(), placeholder, textStyle, leadingIcon, onClear, inner) }
    )
}

/** Variante con selección de texto controlada (para preseleccionar el nombre al renombrar). */
@Composable
fun PlainTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
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
        decorationBox = { inner -> FieldFrame(value.text.isEmpty(), placeholder, textStyle, null, null, inner) }
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

/**
 * Pide un nombre. El teclado se abre solo y, al renombrar un archivo, queda seleccionado el nombre sin
 * la extensión ([selectBaseName]). Lo escrito sobrevive a girar la pantalla.
 */
@Composable
fun NameInputDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    selectBaseName: Boolean = false
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val dot = initialValue.lastIndexOf('.')
        val end = if (selectBaseName && dot > 0) dot else initialValue.length
        mutableStateOf(TextFieldValue(initialValue, TextRange(0, end)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (e: IllegalStateException) {
            // el campo aún no estaba listo: el usuario puede tocarlo
        }
    }
    ModalOverlay(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(16.dp))
        PlainTextField(
            value = field,
            onValueChange = { field = it },
            placeholder = stringResource(R.string.name_hint),
            modifier = Modifier.focusRequester(focusRequester),
            onDone = { if (field.text.isNotBlank()) onConfirm(field.text) }
        )
        Spacer(Modifier.size(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PlainTextButton(stringResource(R.string.cancel), onDismiss)
            PlainTextButton(stringResource(R.string.ok)) { if (field.text.isNotBlank()) onConfirm(field.text) }
        }
    }
}

/** Diálogo de confirmación genérico (título, texto y una acción) para la papelera y los avisos. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalOverlay(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PlainTextButton(stringResource(R.string.cancel), onDismiss)
            PlainTextButton(confirmLabel, onConfirm)
        }
    }
}

/** Eliminar: a la papelera (se puede deshacer) o para siempre. */
@Composable
fun DeleteChoiceDialog(
    names: List<String>,
    trashDays: Int,
    onTrash: () -> Unit,
    onPermanent: () -> Unit,
    onDismiss: () -> Unit
) {
    val what = if (names.size == 1) {
        stringResource(R.string.quoted_name, names.first())
    } else {
        pluralText(R.plurals.items_count, names.size, names.size)
    }
    ModalOverlay(onDismiss = onDismiss) {
        Text(stringResource(R.string.delete_title, what), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(12.dp))
        Text(
            pluralText(R.plurals.delete_body, trashDays, trashDays),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            PlainTextButton(stringResource(R.string.move_to_trash), onTrash)
            PlainTextButton(stringResource(R.string.delete_permanently), onPermanent)
            PlainTextButton(stringResource(R.string.cancel), onDismiss)
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

/**
 * Texto con plural ("1 elemento" / "5 elementos") según el idioma activo. Usa las reglas de plurales del sistema,
 * así funciona igual en inglés y en español (y en cualquier idioma que se añada después).
 */
@Composable
fun pluralText(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    LocalContext.current.resources.getQuantityString(id, count, *args)

/** Pregunta qué hacer al tocar un ZIP: extraerlo aquí o abrirlo con otra app. */
@Composable
fun ZipPromptDialog(name: String, onExtract: () -> Unit, onOpenWith: () -> Unit, onDismiss: () -> Unit) {
    ModalOverlay(onDismiss = onDismiss) {
        Text(stringResource(R.string.quoted_name, name), style = MaterialTheme.typography.titleLarge, maxLines = 2)
        Spacer(Modifier.size(12.dp))
        Text(
            stringResource(R.string.zip_prompt_text),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            PlainTextButton(stringResource(R.string.extract_here), onExtract)
            PlainTextButton(stringResource(R.string.open_with_other), onOpenWith)
            PlainTextButton(stringResource(R.string.cancel), onDismiss)
        }
    }
}

/**
 * Franja de progreso de una operación larga (copiar, mover, comprimir...): título, avance, y un botón para
 * cancelar. La barra se dibuja a mano (sin animación) y solo aparece si la operación tarda un poco.
 */
@Composable
fun ProgressStrip(info: ProgressInfo, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info.title + "…", style = MaterialTheme.typography.titleSmall)
                if (info.detail.isNotEmpty()) Text(info.detail, style = MaterialTheme.typography.bodySmall)
            }
            PlainTextButton(stringResource(R.string.cancel), onCancel)
        }
        val fraction = info.fraction
        if (fraction != null) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
