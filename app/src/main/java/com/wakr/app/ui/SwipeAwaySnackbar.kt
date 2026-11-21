package com.wakr.app.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
internal fun SwipeAwaySnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        key(data) {
            SwipeAwayBanner(onDismiss = data::dismiss) { Snackbar(data) }
        }
    }
}

@Composable
internal fun SwipeAwayBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val state = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = { it * .35f },
        )
    }
    SwipeToDismissBox(
        state = state,
        backgroundContent = {},
        modifier = modifier,
        onDismiss = { onDismiss() },
        content = content,
    )
}
