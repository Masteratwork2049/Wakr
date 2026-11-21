package com.wakr.app.ui.sheets

import android.animation.ValueAnimator
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

/** Full-screen alarm surfaces still keep the content clear of cutouts, system bars and the IME. */
@Composable
internal fun AlarmFullscreenDialog(
    onClose: () -> Unit,
    slideVertically: Boolean = false,
    content: @Composable ((() -> Unit) -> Unit) -> Unit,
) {
    val motion = slideVertically && ValueAnimator.areAnimatorsEnabled()
    val offset = remember { Animatable(if (motion) 1f else 0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    fun closeAfter(action: () -> Unit) {
        if (closing) return
        closing = true
        if (!motion) action() else scope.launch {
            offset.animateTo(1f, tween(220, easing = FastOutLinearInEasing))
            action()
        }
    }
    LaunchedEffect(motion, closing) {
        if (motion && !closing) offset.animateTo(0f, tween(280, easing = LinearOutSlowInEasing))
    }
    Dialog(onDismissRequest = { closeAfter(onClose) }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false,
    )) {
        val view = LocalView.current
        val lightSurface = MaterialTheme.colorScheme.surface.luminance() > .5f
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                if (slideVertically) {
                    window.setWindowAnimations(0)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightSurface
                    isAppearanceLightNavigationBars = lightSurface
                }
            }
        }
        Surface(
            Modifier.fillMaxSize().graphicsLayer { translationY = size.height * offset.value },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 650.dp).fillMaxSize()) { content(::closeAfter) }
            }
        }
    }
}
