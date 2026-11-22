package com.wakr.app.ui.sheets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.wakr.app.core.WorldCity
import com.wakr.app.data.WorldCityCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One adaptive modal presentation for phones and wide windows; city data stays entirely offline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCitySheet(existing: List<WorldCity>, nowUtcMillis: Long,
    onAdd: (WorldCity) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val assets = LocalContext.current.assets
    val catalog by produceState<WorldCityCatalog?>(null, assets) {
        value = withContext(Dispatchers.IO) { WorldCityCatalog.load(assets) }
    }
    val results = remember(query, existing, catalog) { catalog?.choices(existing, query).orEmpty() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val ime = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val topInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val available = (windowHeight - ime - topInset - 48.dp).coerceAtLeast(0.dp)
    val compactHeight = minOf(windowHeight * .44f - 32.dp, 420.dp, available)
        .coerceAtLeast(minOf(280.dp, available))
    val searchHeight = minOf(windowHeight * .82f, available)
    // Follow platform IME inset frames directly; animate only the expansion fraction so
    // keyboard movement cannot cancel/restart the expansion or introduce a height jump.
    val expansion by animateFloatAsState(if (searching) 1f else 0f,
        tween(260, easing = FastOutSlowInEasing), label = "locationSheetExpansion")
    val expandedHeight = compactHeight + (searchHeight - compactHeight) * expansion
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
    LaunchedEffect(focused, sheet.hasPartiallyExpandedState) {
        if (focused && sheet.hasPartiallyExpandedState) sheet.expand()
    }
    fun dismiss(add: WorldCity? = null) {
        if (closing) return
        if (add != null && existing.any { it.id == add.id }) return
        closing = true
        focus.clearFocus(); keyboard?.hide()
        scope.launch {
            try {
                sheet.hide()
                if (!sheet.isVisible) {
                    if (add != null) onAdd(add)
                    onDismiss()
                }
            } finally { closing = false }
        }
    }
    val searchColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        tween(180), label = "locationSearchFocus")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        shape = AlarmSheetShape, dragHandle = {
            Box(Modifier.pointerInput(Unit) {
                // Observe without consuming: Material still owns the drag and settling gesture.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var travel = 0f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        travel += event.changes.firstOrNull()?.positionChange()?.y ?: 0f
                        if (travel < -viewConfiguration.touchSlop) searching = true
                    } while (event.changes.any { it.pressed })
                }
            }) { AlarmEditorDragHandle() }
        },
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().height(expandedHeight).navigationBarsPadding()) {
            SelectionSheetHeader("Add Location", onCancel = { dismiss() })
            TextField(value = query, onValueChange = { query = it; searching = true },
                label = { Text("Search cities") }, singleLine = true, enabled = !closing,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = searchColor,
                    unfocusedContainerColor = searchColor, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) searching = true
                })
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Text(if (query.isBlank()) "Suggested Cities" else "Search Results",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
                if (catalog == null) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp))
                } else if (results.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (query.isBlank()) "You're up to date" else "No matching cities",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (query.isBlank()) "Your saved locations are excluded from suggestions."
                            else "Try a different city or country.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(results, key = { it.id }) { city ->
                    ListItem(headlineContent = { Text(city.city) }, supportingContent = { Text(city.region) },
                        leadingContent = { Icon(Icons.Outlined.LocationOn, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Text(localTime(city.zone, nowUtcMillis),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(enabled = !closing) { dismiss(city) })
                }
            }
        }
    }
}

private fun localTime(zone: ZoneId, nowUtcMillis: Long): String =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowUtcMillis), zone)
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))

/** Platform tzdb names preserve multi-segment regions without a separate city database. */
internal fun prettify(id: String): WorldCity {
    val parts = id.split("/")
    val city = parts.last().replace('_', ' ')
    val region = if (parts.size == 3) parts[1].replace('_', ' ') else parts.first().replace('_', ' ')
    return WorldCity(ZoneId.of(id), city, region)
}
