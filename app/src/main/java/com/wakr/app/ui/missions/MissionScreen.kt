package com.wakr.app.ui.missions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The ringing and practice flows use the same hierarchy and challenge surface. */
@Composable
internal fun MissionScreen(
    title: String,
    subtitle: String,
    progress: Float?,
    onBack: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
    challenge: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                }
                Column {
                    Text(title, style = MaterialTheme.typography.headlineMediumEmphasized,
                        color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                }
            }
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                color = colors.surfaceContainerHigh, contentColor = colors.onSurface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = challenge)
            }
            actions()
        }
    }
}
