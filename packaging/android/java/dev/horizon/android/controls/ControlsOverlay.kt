package dev.horizon.android.controls

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import dev.horizon.android.R
import dev.horizon.android.controls.ui.ControlEditor
import dev.horizon.android.controls.ui.ControlsTheme
import dev.horizon.android.controls.ui.HorizonCardStyle
import dev.horizon.android.controls.ui.OverlayMenu

/**
 * The launcher's touch layout, drawn over the game.
 *
 * The game is a NativeActivity: it owns its window's input queue, so a view added to that window
 * never sees a touch. The overlay therefore lives in a window of its own (as the splash does) and
 * talks to the engine through [HorizonBridge]. The window is always touchable, so the buttons work in
 * menus too; in menus without a virtual mouse, touches that miss every button are handed to the
 * engine as native touches ([ControlsView]), so its own touch handling still works there.
 */
class ControlsOverlay(private val activity: Activity) : ControlsHost {
    private val settings = ControlSettings(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var view: ControlsView? = null
    private var frame: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var ui by mutableStateOf(OverlayUi.NONE)

    private val sync = object : Runnable {
        override fun run() {
            val v = view ?: return
            v.setCaptured(HorizonBridge.isCaptured())
            publishGameArea(v)
            handler.postDelayed(this, 100)
        }
    }

    private var publishedArea = floatArrayOf(Float.NaN, 0f, 0f, 0f)

    /**
     * The overlay covers the whole display, display cutout included, but the game's window may start after
     * it (the black strip beside a notch). The engine needs the game window's rectangle in the overlay's
     * pixels, or the cursor and forwarded touches land that far off from where they are drawn.
     */
    private fun publishGameArea(v: ControlsView) {
        val game = activity.findViewById<View>(android.R.id.content) ?: activity.window.decorView
        if (game.width <= 0 || game.height <= 0 || v.width <= 0 || v.height <= 0) return
        val g = IntArray(2)
        val o = IntArray(2)
        game.getLocationOnScreen(g)
        v.getLocationOnScreen(o)
        val next = floatArrayOf((g[0] - o[0]).toFloat(), (g[1] - o[1]).toFloat(), game.width.toFloat(), game.height.toFloat())
        if (next.contentEquals(publishedArea)) return
        publishedArea = next
        HorizonBridge.setArea(next[0], next[1], next[2], next[3])
    }

    val showing: Boolean get() = dialog != null

    fun show() {
        if (dialog != null || !settings.overlayEnabled) return
        runCatching {
            val controls = ControlsView(
                activity,
                this,
                settings,
                ControlLayouts.load(activity, settings.layout, activity.getString(R.string.controls_default_name)),
            )
            controls.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                HorizonBridge.setScale((r - l).toFloat(), (b - t).toFloat())
            }
            val d = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
            d.requestWindowFeature(Window.FEATURE_NO_TITLE)
            d.setCancelable(false)
            val host = FrameLayout(activity)
            host.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            d.setContentView(
                host,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            frame = host
            d.window?.let { win ->
                win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                win.setFormat(PixelFormat.TRANSLUCENT)
                win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                // Not focusable: keys and gamepads keep going to the game's window.
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_FULLSCREEN
                        or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val lp = win.attributes
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    win.attributes = lp
                }
                @Suppress("DEPRECATION")
                win.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
            }
            view = controls
            dialog = d
            d.show()
            HorizonBridge.setActive(true)
            handler.post(sync)
        }.onFailure {
            // A broken overlay must never stop the game: fall back to the engine's own controls.
            Log.e(TAG, "Touch overlay could not start: ${it.message}", it)
            hide()
        }
    }

    fun hide() {
        handler.removeCallbacks(sync)
        runCatching { composeView?.disposeComposition() }
        runCatching { owner?.destroy() }
        composeView = null
        owner = null
        frame = null
        ui = OverlayUi.NONE
        HorizonBridge.setActive(false)
        runCatching { view?.releaseAll() }
        runCatching { dialog?.dismiss() }
        view = null
        dialog = null
    }

    /** Compose is built the first time the menu handle opens, so a game that never opens it pays nothing. */
    private fun ensureCompose() {
        if (composeView != null) return
        val host = frame ?: return
        val win = dialog?.window ?: return
        val o = OverlayOwner()
        ComposeHostOwners.install(win.decorView, o, o, o)
        val cv = ComposeView(activity)
        cv.visibility = View.GONE
        cv.setContent {
            val current = ui
            ControlsTheme {
                when (current) {
                    OverlayUi.MENU -> ControlsTheme(HorizonCardStyle) {
                        OverlayMenu(
                            settings = settings,
                            onClose = { switchUi(OverlayUi.NONE) },
                            onPause = {
                                switchUi(OverlayUi.NONE)
                                toggleMenu()
                            },
                            onEdit = { switchUi(OverlayUi.EDIT) },
                            onLayout = { reloadLayout() },
                            onSettingsChanged = { view?.reloadSettings() },
                        )
                    }
                    OverlayUi.EDIT -> view?.let { ControlEditor(it, settings) { switchUi(OverlayUi.NONE) } }
                    OverlayUi.NONE -> {}
                }
            }
        }
        host.addView(cv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        owner = o
        composeView = cv
    }

    private fun reloadLayout() {
        view?.setLayout(ControlLayouts.load(activity, settings.layout, activity.getString(R.string.controls_default_name)))
    }

    private fun switchUi(next: OverlayUi) {
        val v = view ?: return
        if (next != OverlayUi.NONE) ensureCompose()
        val was = ui
        ui = next
        composeView?.visibility = if (next == OverlayUi.NONE) View.GONE else View.VISIBLE
        v.setEditMode(next == OverlayUi.EDIT)
        // The editor has text fields, so its window takes focus; otherwise keys stay with the game.
        dialog?.window?.let { win ->
            if (next == OverlayUi.EDIT) win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            else win.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        if (was == OverlayUi.EDIT && next != OverlayUi.EDIT) reloadLayout()
        v.reloadSettings()
    }

    override fun openMenu() {
        handler.post { if (ui == OverlayUi.NONE) switchUi(OverlayUi.MENU) }
    }

    override fun sendKey(code: Int, down: Boolean) {
        val name = Keycodes.engineName(code) ?: return
        HorizonBridge.command("key $name ${if (down) "down" else "up"}")
    }

    // The engine opens its own on-screen keyboard when chat opens.
    override fun toggleKeyboard() {
        HorizonBridge.command("key key.keyboard.t tap")
    }

    override fun toggleMenu() {
        HorizonBridge.command("key key.keyboard.escape tap")
    }

    override fun sendText(text: String) {
        if (text.isNotEmpty()) HorizonBridge.command("type $text")
    }

    private companion object {
        const val TAG = "ControlsOverlay"
    }
}
