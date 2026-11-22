package com.wakr.app.alarm

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wakr.app.core.ClockTimer
import com.wakr.app.core.TimerState
import com.wakr.app.data.ClockSettings
import com.wakr.app.data.ClockStore
import com.wakr.app.data.DarkMode
import com.wakr.app.ui.rememberElapsedTicker
import com.wakr.app.ui.screens.StopwatchDial
import com.wakr.app.ui.screens.countdownText
import com.wakr.app.ui.theme.ClockFace
import com.wakr.app.ui.theme.ClockTheme
import kotlinx.coroutines.launch

class TimerRingActivity : ComponentActivity() {
    private var requestedId by mutableLongStateOf(-1L)
    private var displayedId: Long? = null
    private var dismissing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedId = intent.getLongExtra(AlarmReceiver.EXTRA_ID, -1L)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_ALARM
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        val store = ClockStore(applicationContext)
        setContent {
            val settings by store.settings.collectAsStateWithLifecycle(ClockSettings())
            val timers by store.timers.collectAsStateWithLifecycle(initialValue = null)
            val list = timers ?: return@setContent
            var selectedId by rememberSaveable(requestedId) { mutableLongStateOf(requestedId) }
            val finished = list.filter { it.state == TimerState.FINISHED }
            val timer = finished.firstOrNull { it.id == selectedId }
                ?: finished.filter { it.chimePending }.minWithOrNull(timerAlertOrder)
            SideEffect { displayedId = timer?.id }
            LaunchedEffect(timer?.id) {
                if (timer == null) finish() else selectedId = timer.id
            }
            if (timer != null) ClockTheme(settings.theme.copy(darkMode = DarkMode.DARK, amoledBlack = true)) {
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
                TimerExpiredScreen(timer) {
                    if (!dismissing) {
                        dismissing = true
                        lifecycleScope.launch {
                            try { TimerRuntime.cancel(applicationContext, timer.id) }
                            finally { dismissing = false }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedId = intent.getLongExtra(AlarmReceiver.EXTRA_ID, -1L)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val id = displayedId
        if (id != null && !dismissing && !isFinishing && AlarmService.isTimerChiming(id)) {
            launchAlertIfAllowed(this, screenIntent(this, id))
        }
    }

    override fun onStop() {
        super.onStop()
        val id = displayedId
        if (id != null && !dismissing && !isFinishing && !isChangingConfigurations && AlarmService.isTimerChiming(id)) {
            sendAlertReturn(this, pendingIntent(this, id))
        }
    }

    companion object {
        internal fun screenIntent(context: Context, id: Long) =
            Intent(context, TimerRingActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AlarmReceiver.EXTRA_ID, id)

        internal fun pendingIntent(context: Context, id: Long): PendingIntent =
            alertActivityIntent(context, id.toInt(),
                screenIntent(context, id).setData(android.net.Uri.parse("wakr://timer-alert/$id")))
    }
}

/** The oldest deadline owns the screen and audio when several timers expire together. */
internal val timerAlertOrder = compareBy<ClockTimer>({ it.deadlineElapsedMillis }, { it.id })

internal fun timerOverdueTurns(overdueMillis: Long): Float =
    -(overdueMillis.coerceAtLeast(0L) % 60_000L) / 60_000f

@Composable
internal fun TimerExpiredScreen(timer: ClockTimer, onDismiss: () -> Unit) {
    val now = rememberElapsedTicker()
    val seconds by remember(timer) {
        derivedStateOf { (now.value - timer.deadlineElapsedMillis).coerceAtLeast(0L) / 1_000L }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 16.dp)) {
        val dialSize = minOf(410.dp, maxWidth, (maxHeight - 126.dp).coerceAtLeast(128.dp))
        Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
            StopwatchDial(
                modifier = Modifier.size(dialSize),
                turns = { timerOverdueTurns(now.value - timer.deadlineElapsedMillis) },
                selected = true,
            ) {
                val readout = remember(seconds) { if (seconds > 0) "−" + countdownText(seconds) else countdownText(0) }
                val readoutColor = if (seconds > 0) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.primary
                val size = with(LocalDensity.current) { (dialSize * if (seconds >= 3_600) .105f else .135f).toSp() }
                Column(Modifier.padding(horizontal = dialSize * .12f),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(readout, color = readoutColor, fontSize = size, lineHeight = size,
                        fontFamily = ClockFace.family(opticalSize = 34f, width = 100f, weight = 500, roundness = ClockFace.ROUNDED),
                        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                        maxLines = 1)
                    Text(timer.label.ifBlank { "Time’s up" }, color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis, maxLines = 1)
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Text("Dismiss")
            }
        }
    }
}

/** Android can decline an activity launch when the app is no longer visible. */
internal fun launchAlertIfAllowed(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (error: SecurityException) {
        android.util.Log.d("WakrAlert", "Android did not allow the alert to open", error)
    }
}

/** The intent is explicit, immutable, and only targets a Wakr alert activity. */
internal fun alertActivityIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent {
    val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        ).toBundle()
    } else null
    return PendingIntent.getActivity(context, requestCode, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options)
}

/** Retry after the system has moved the task away. Android still decides whether to launch. */
internal fun sendAlertReturn(context: Context, alert: PendingIntent) {
    try {
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            ).toBundle()
        } else null
        alert.send(context, 0, null, null, null, null, options)
    } catch (error: PendingIntent.CanceledException) {
        android.util.Log.d("WakrAlert", "The alert no longer has an active launch intent", error)
    } catch (error: SecurityException) {
        android.util.Log.d("WakrAlert", "Android declined the return request", error)
    }
}
