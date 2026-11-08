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
import dev.horizon.android.R

/**
 * The launcher's touch layout, drawn over the game.
 *
 * The game is a NativeActivity: it owns its window's input queue, so a view added to that window
 * never sees a touch. The overlay therefore lives in a window of its own (as the splash does) and
 * talks to the engine through [HorizonBridge]. In menus without a virtual mouse the window lets
 * touches through, so the engine's own touch handling still works there.
 */
class ControlsOverlay(private val activity: Activity) : ControlsHost {
    private val settings = ControlSettings(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var view: ControlsView? = null
    private var passThrough = false

    private val sync = object : Runnable {
        override fun run() {
            val v = view ?: return
            val captured = HorizonBridge.isCaptured()
            v.setCaptured(captured)
            setPassThrough(!captured && !v.virtualMouse)
            handler.postDelayed(this, 100)
        }
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
            d.setContentView(
                controls,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
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
        HorizonBridge.setActive(false)
        runCatching { view?.releaseAll() }
        runCatching { dialog?.dismiss() }
        view = null
        dialog = null
        passThrough = false
    }

    private fun setPassThrough(on: Boolean) {
        if (on == passThrough) return
        passThrough = on
        val win = dialog?.window ?: return
        if (on) win.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        else win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
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
