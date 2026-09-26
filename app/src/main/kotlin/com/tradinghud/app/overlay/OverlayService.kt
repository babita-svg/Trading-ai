package com.tradinghud.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tradinghud.app.capture.CaptureService
import com.tradinghud.app.db.AppDatabase
import com.tradinghud.app.gemini.GeminiRepository
import com.tradinghud.app.overlay.ui.*

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private lateinit var viewModel: OverlayViewModel

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Trading HUD Overlay")
            .setContentText("Tap the floating bubble to analyze chart")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(2, notification)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val db = AppDatabase.instance(this)
        val geminiRepo = GeminiRepository()
        val captureSvc = CaptureServiceHolder.captureService
        if (captureSvc != null) {
            viewModel = OverlayViewModel(db, geminiRepo, captureSvc)
        } else {
            stopSelf()
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(CustomLifecycleOwner())
            setViewTreeSavedStateRegistryOwner(CustomSavedStateRegistryOwner())
            setContent {
                val state by viewModel.uiState.collectAsState()
                when (state) {
                    is OverlayState.Bubble -> BubbleView(
                        onTap = { viewModel.onBubbleTap() },
                        onDrag = { dx, dy ->
                            params.x = (params.x + dx.toInt())
                            params.y = (params.y + dy.toInt())
                            try {
                                windowManager.updateViewLayout(this@apply, params)
                            } catch (_: Exception) {}
                        }
                    )
                    is OverlayState.Input -> InputPanel(
                        onConfirm = { capital, market -> viewModel.onTap(capital, market) },
                        onCancel = { viewModel.cancel() }
                    )
                    is OverlayState.Working -> WorkingPanel(onCancel = { viewModel.cancel() })
                    is OverlayState.Verdict -> VerdictPanel(
                        verdict = state as OverlayState.Verdict,
                        onLogResult = { won, amount -> viewModel.logResult(won, amount) },
                        onDismiss = { viewModel.dismiss() }
                    )
                    is OverlayState.Blocked -> BlockedPanel(
                        reason = (state as OverlayState.Blocked).message,
                        onDismiss = { viewModel.dismiss() }
                    )
                }
            }
        }

        windowManager.addView(overlayView, params)
    }

    override fun onDestroy() {
        overlayView?.let { windowManager.removeView(it) }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "trading_hud_overlay"
    }
}

object CaptureServiceHolder {
    var captureService: CaptureService? = null
}

class CustomLifecycleOwner : androidx.lifecycle.LifecycleOwner {
    private val registry = androidx.lifecycle.LifecycleRegistry(this)
    init { registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
    override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
}

class CustomSavedStateRegistryOwner : androidx.savedstate.SavedStateRegistryOwner {
    private val controller = androidx.savedstate.SavedStateRegistryController.create(this)
    init { controller.performRestore(null) }
    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = controller.savedStateRegistry
    override val lifecycle: androidx.lifecycle.Lifecycle get() = CustomLifecycleOwner().lifecycle
}
