/*
 * CallPromptOverlay - the non-modal floating approval card. It is hosted by the
 * foreground ControlService with SYSTEM_ALERT_WINDOW, above whatever app is in front.
 * Without the overlay permission the service keeps the notification fallback.
 */
package cz.slush.adbanywhere.ui

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import cz.slush.adbanywhere.firewall.PromptRequest
import cz.slush.adbanywhere.service.AppLog
import cz.slush.adbanywhere.service.ControlService

class CallPromptOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val lifecycleOwner = object : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedState.savedStateRegistry

        init {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
    }
    private var view: View? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show(request: PromptRequest): Boolean {
        hide()
        val manager = windowManager ?: return false
        val allowed = canShow()
        AppLog.info("firewall", "overlay canShow=$allowed")
        if (!allowed) return false
        val compose = ComposeView(context).apply {
            // A malicious overlay could cover the card and let a tap fall
            // through to the real Allow buttons. The framework then refuses a
            // touch that arrived through an obscured window.
            setFilterTouchesWhenObscured(true)
            attachLifecycleOwner(this)
            setContent {
                AdbAnywhereTheme {
                    CallPrompt(
                        request,
                        onDecision = { decision, rule ->
                            ControlService.startPromptResponse(
                                context,
                                request.id,
                                decision,
                                rule?.toJson()?.toString(),
                            )
                        },
                        inline = true,
                    )
                }
            }
        }
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * density).toInt()
        }
        val added = runCatching { manager.addView(compose, params) }
        added.exceptionOrNull()?.let {
            AppLog.warn("firewall", "overlay add failed: ${it.message}")
        }
        if (added.isSuccess) view = compose
        return added.isSuccess
    }

    /**
     * ViewTreeLifecycleOwner lives in the Android variant of lifecycle-runtime,
     * which is not on this module's compile classpath. The class is public API;
     * set it reflectively so the ComposeView can create its recomposer.
     */
    private fun attachLifecycleOwner(view: View) {
        runCatching {
            val owner = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            val set = owner.getMethod("set", View::class.java, LifecycleOwner::class.java)
            set.invoke(null, view, lifecycleOwner)
        }.onFailure {
            AppLog.warn("firewall", "overlay lifecycle: ${it.message}")
        }
        runCatching {
            val owner = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            val set = owner.getMethod(
                "set",
                View::class.java,
                SavedStateRegistryOwner::class.java,
            )
            set.invoke(null, view, lifecycleOwner)
        }.onFailure {
            AppLog.warn("firewall", "overlay saved state: ${it.message}")
        }
    }

    fun hide() {
        val current = view ?: return
        view = null
        runCatching { windowManager?.removeView(current) }
    }
}
