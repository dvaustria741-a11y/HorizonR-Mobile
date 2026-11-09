package dev.horizon.android.controls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import dev.horizon.android.R
import dev.horizon.android.controls.Action
import dev.horizon.android.controls.ControlButton
import dev.horizon.android.controls.ControlLayout
import dev.horizon.android.controls.ControlSettings
import dev.horizon.android.controls.ControlStick
import dev.horizon.android.controls.Style
import dev.horizon.android.controls.Visibility
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

interface ControlsHost {
    fun sendKey(code: Int, down: Boolean)
    fun toggleKeyboard()
    fun toggleMenu()

    /** The menu handle was tapped: the overlay opens its menu; other hosts keep the old pause-menu behavior. */
    fun openMenu() = toggleMenu()
    fun sendText(text: String)
}

sealed interface Selection {
    data class Button(val index: Int) : Selection
    data class Stick(val index: Int) : Selection
}

@SuppressLint("ViewConstructor")
class ControlsView(
    context: Context,
    private val host: ControlsHost,
    private val settings: ControlSettings,
    initial: ControlLayout,
    private val showHandle: Boolean = true,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val uiHandler = Handler(Looper.getMainLooper())

    var layout: ControlLayout = initial
        private set
    private var layerVisible = mutableMapOf<String, Boolean>()
    private var layerVisibility = mapOf<String, Visibility>()
    private var buttonRects = arrayOf<RectF>()
    private var stickCenters = arrayOf<FloatArray>()
    private var pressed = IntArray(0)
    private var toggled = BooleanArray(0)
    private var stickHeld = arrayOf<List<Action>>()
    private var stickKnob = arrayOf<FloatArray>()
    private var stickArmed = BooleanArray(0)
    private var stickLocked = BooleanArray(0)
    private var stickSneak = BooleanArray(0)
    private var stickLastTap = LongArray(0)
    private val heldKeys = HashMap<Int, Int>()
    private val heldMouse = IntArray(3)
    private val scrollRepeats = HashMap<Float, Runnable>()
    private val menuRect = RectF()

    var captured = false
        private set
    var virtualMouse = settings.virtualMouse
        private set
    var editMode = false
        private set
    var selection: Selection? = null
        private set
    var onSelectionChanged: ((Selection?) -> Unit)? = null
    var onLayoutEdited: (() -> Unit)? = null
    private var cursorX = -1f
    private var cursorY = -1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconRect = RectF()
    private val menuIcon: Bitmap? by lazy { runCatching { BitmapFactory.decodeResource(resources, R.drawable.ic_ctrl_menu) }.getOrNull() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val cursorPath = Path()
    private var cursorDrawable: Drawable? = null
    private var cursorMovie: Movie? = null
    private var movieStart = 0L

    private val defaultStyle = Style(
        fill = Color.argb(120, 24, 24, 28),
        fillPressed = Color.argb(215, 120, 120, 130),
        text = Color.WHITE,
        textPressed = Color.WHITE,
        stroke = Color.argb(170, 255, 255, 255),
        strokeDp = 1f,
        radiusDp = 10f,
        textSp = 15f,
    )

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    private sealed class Role {
        class Btn(var index: Int, var lastX: Float, var lastY: Float) : Role()
        class Stick(val index: Int) : Role()
        class Look(val startX: Float, val startY: Float, var lastX: Float, var lastY: Float, val downTime: Long) : Role() {
            var moved = false
            var mining = false
            var longPress: Runnable? = null
        }
        class Mouse(val startX: Float, val startY: Float, var lastX: Float, var lastY: Float, val downTime: Long) : Role() {
            var moved = false
            var dragging = false
            var consumed = false
            var longPress: Runnable? = null
        }
        class Scroll(var lastY: Float, val downTime: Long) : Role() {
            var travelled = 0f
            var scrolled = false
        }
        class Handle(val startX: Float, val startY: Float, val offX: Float, val offY: Float) : Role() {
            var moved = false
        }
        class Drag(val target: Selection, val offX: Float, val offY: Float) : Role()
        data object Ignore : Role()

        /** A finger over empty space in a menu without the virtual mouse: the engine handles it as a touch. */
        class Forward(var lastX: Float, var lastY: Float) : Role()
    }

    private val roles = HashMap<Int, Role>()

    init {
        setLayout(initial)
        loadCursor()
    }

    fun setLayout(next: ControlLayout) {
        releaseAll()
        layout = next
        layerVisible = next.layers.associate { it.id to it.visible }.toMutableMap()
        layerVisibility = next.layers.associate { it.id to it.visibility }
        buttonRects = Array(next.buttons.size) { RectF() }
        stickCenters = Array(next.sticks.size) { floatArrayOf(0f, 0f, 0f) }
        pressed = IntArray(next.buttons.size)
        toggled = BooleanArray(next.buttons.size)
        stickHeld = Array(next.sticks.size) { emptyList() }
        stickKnob = Array(next.sticks.size) { floatArrayOf(0f, 0f) }
        stickArmed = BooleanArray(next.sticks.size)
        stickLocked = BooleanArray(next.sticks.size)
        stickSneak = BooleanArray(next.sticks.size)
        stickLastTap = LongArray(next.sticks.size)
        relayout()
    }

    fun editLayout(next: ControlLayout, select: Selection? = selection) {
        val keep = layerVisible
        setLayout(next)
        keep.forEach { (k, v) -> if (k in layerVisible) layerVisible[k] = v }
        selection = select?.takeIf {
            when (it) {
                is Selection.Button -> it.index in next.buttons.indices
                is Selection.Stick -> it.index in next.sticks.indices
            }
        }
        onSelectionChanged?.invoke(selection)
        onLayoutEdited?.invoke()
        invalidate()
    }

    fun setEditMode(on: Boolean) {
        releaseAll()
        editMode = on
        selection = null
        onSelectionChanged?.invoke(null)
        invalidate()
    }

    /** The style a button is drawn with, so the editor starts from what is on screen. */
    fun effectiveStyle(b: ControlButton): Style = b.style ?: defaultStyle

    fun effectiveStyle(s: ControlStick): Style = s.style ?: defaultStyle

    /** Where the selected button or stick is on screen, in this view's pixels. */
    fun selectionRect(): RectF? = when (val s = selection) {
        is Selection.Button -> buttonRects.getOrNull(s.index)?.let { RectF(it) }
        is Selection.Stick -> stickCenters.getOrNull(s.index)?.let { RectF(it[0] - it[2], it[1] - it[2], it[0] + it[2], it[1] + it[2]) }
        null -> null
    }

    fun select(next: Selection?) {
        selection = next
        onSelectionChanged?.invoke(next)
        invalidate()
    }

    fun reloadSettings() {
        virtualMouse = settings.virtualMouse
        relayout()
        loadCursor()
        invalidate()
    }

    fun setCaptured(on: Boolean) {
        if (on == captured) return
        releaseAll()
        captured = on
        if (!on && virtualMouse) {
            centerCursorIfUnset()
            HorizonBridge.cursor(cursorX, cursorY)
        }
        invalidate()
    }

    fun setVirtualMouse(on: Boolean) {
        virtualMouse = on
        settings.virtualMouse = on
        if (on && !captured) {
            centerCursorIfUnset()
            HorizonBridge.cursor(cursorX, cursorY)
        }
        invalidate()
    }

    private fun centerCursorIfUnset() {
        if (cursorX < 0f) {
            cursorX = width / 2f
            cursorY = height / 2f
        }
    }

    fun releaseAll() {
        roles.forEach { (pointer, role) ->
            when (role) {
                is Role.Look -> role.longPress?.let(uiHandler::removeCallbacks)
                is Role.Mouse -> role.longPress?.let(uiHandler::removeCallbacks)
                is Role.Forward -> HorizonBridge.touch(pointer, TOUCH_CANCEL, role.lastX, role.lastY)
                else -> {}
            }
        }
        roles.clear()
        pressed.fill(0)
        for (i in stickHeld.indices) {
            stickArmed[i] = false
            stickLocked[i] = false
            stickHeld[i] = emptyList()
            stickKnob[i][0] = 0f
            stickKnob[i][1] = 0f
        }
        toggled.fill(false)
        stickSneak.fill(false)
        val hadInput = heldKeys.isNotEmpty() || heldMouse.any { it > 0 }
        heldKeys.keys.toList().forEach { host.sendKey(it, false) }
        heldKeys.clear()
        for (b in 0..2) if (heldMouse[b] > 0) mouse(b, false)
        heldMouse.fill(0)
        scrollRepeats.values.forEach(uiHandler::removeCallbacks)
        scrollRepeats.clear()
        if (hadInput || captured) HorizonBridge.command("release")
        invalidate()
    }

    private fun loadCursor() {
        (cursorDrawable as? AnimatedImageDrawable)?.stop()
        cursorDrawable?.callback = null
        cursorDrawable = null
        cursorMovie = null
        val path = settings.cursorImage
        if (path.isEmpty()) return
        val file = File(path)
        if (!file.isFile) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                d.callback = this
                if (d is AnimatedImageDrawable) {
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    d.start()
                }
                cursorDrawable = d
            } else if (file.name.endsWith(".gif", true)) {
                @Suppress("DEPRECATION")
                cursorMovie = Movie.decodeFile(file.absolutePath)
                movieStart = SystemClock.uptimeMillis()
            } else {
                BitmapFactory.decodeFile(file.absolutePath)?.let { cursorDrawable = BitmapDrawable(resources, it) }
            }
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === cursorDrawable || super.verifyDrawable(who)

    private fun matches(v: Visibility) = editMode || when (v) {
        Visibility.ALWAYS -> true
        Visibility.IN_GAME -> captured
        Visibility.IN_MENU -> !captured
    }

    private fun shown(layer: String, v: Visibility) =
        (editMode || layerVisible[layer] != false) && matches(layerVisibility[layer] ?: Visibility.ALWAYS) && matches(v)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
        if (cursorX < 0f && w > 0) {
            cursorX = w / 2f
            cursorY = h / 2f
        }
    }

    private fun buttonSize(b: ControlButton, w: Int, h: Int): Pair<Float, Float> {
        val scale = settings.buttonScale
        return b.width.px(w, h, density) * scale to b.height.px(w, h, density) * scale
    }

    private fun stickSize(s: ControlStick, w: Int, h: Int): Float = s.size.px(w, h, density) * settings.buttonScale

    fun relayout() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        layout.buttons.forEachIndexed { i, b ->
            val (bw, bh) = buttonSize(b, w, h)
            val left = (w - bw) * b.x
            val top = (h - bh) * b.y
            buttonRects[i].set(left, top, left + bw, top + bh)
        }
        layout.sticks.forEachIndexed { i, s ->
            val size = stickSize(s, w, h)
            stickCenters[i][0] = (w - size) * s.x + size / 2f
            stickCenters[i][1] = (h - size) * s.y + size / 2f
            stickCenters[i][2] = size / 2f
        }
        val mw = 52f * density
        val mh = 22f * density
        val left = (w - mw) * settings.menuX.coerceIn(0f, 1f)
        val top = (h - mh) * settings.menuY.coerceIn(0f, 1f)
        menuRect.set(left, top, left + mw, top + mh)
        invalidate()
    }

    private fun vibrate() {
        if (!settings.vibrate) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun key(code: Int, down: Boolean) {
        val count = heldKeys[code] ?: 0
        if (down) {
            heldKeys[code] = count + 1
            if (count == 0) host.sendKey(code, true)
        } else if (count > 0) {
            if (count == 1) {
                heldKeys.remove(code)
                host.sendKey(code, false)
            } else heldKeys[code] = count - 1
        }
    }

    private fun mouse(button: Int, down: Boolean) {
        if (captured) {
            val name = when (button) {
                1 -> "right"
                2 -> "middle"
                else -> "left"
            }
            HorizonBridge.command("mouse $name ${if (down) "down" else "up"}")
        } else {
            HorizonBridge.button(button, down)
        }
    }

    private fun holdMouse(button: Int, down: Boolean) {
        if (down) {
            if (heldMouse[button]++ == 0) mouse(button, true)
        } else if (heldMouse[button] > 0) {
            if (--heldMouse[button] == 0) mouse(button, false)
        }
    }

    private fun down(a: Action) {
        when (a) {
            is Action.Key -> key(a.code, true)
            is Action.Mouse -> holdMouse(a.button, true)
            is Action.Scroll -> if (a.repeat) {
                val r = object : Runnable {
                    override fun run() {
                        HorizonBridge.wheel(a.lines)
                        uiHandler.postDelayed(this, 80)
                    }
                }
                scrollRepeats.put(a.lines, r)?.let(uiHandler::removeCallbacks)
                uiHandler.post(r)
            } else HorizonBridge.wheel(a.lines)
            else -> instant(a)
        }
    }

    private fun up(a: Action) {
        when (a) {
            is Action.Key -> key(a.code, false)
            is Action.Mouse -> holdMouse(a.button, false)
            is Action.Scroll -> if (a.repeat) scrollRepeats.remove(a.lines)?.let(uiHandler::removeCallbacks)
            else -> {}
        }
    }

    private fun tap(a: Action) {
        when (a) {
            is Action.Key -> {
                key(a.code, true)
                key(a.code, false)
            }
            is Action.Mouse -> {
                holdMouse(a.button, true)
                holdMouse(a.button, false)
            }
            is Action.Scroll -> HorizonBridge.wheel(a.lines)
            else -> instant(a)
        }
    }

    private fun instant(a: Action) {
        when (a) {
            Action.Keyboard -> host.toggleKeyboard()
            Action.Menu -> host.toggleMenu()
            Action.ToggleMouse -> setVirtualMouse(!virtualMouse)
            is Action.Text -> host.sendText(a.text)
            is Action.Layer -> {
                for (id in a.ids) {
                    layerVisible[id] = when (a.mode) {
                        Action.LAYER_SHOW -> true
                        Action.LAYER_HIDE -> false
                        else -> layerVisible[id] == false
                    }
                }
                invalidate()
            }
            else -> {}
        }
    }

    private fun buttonAt(x: Float, y: Float): Int {
        for (i in layout.buttons.indices.reversed()) {
            val b = layout.buttons[i]
            if ((editMode || b.interactive) && shown(b.layer, b.visibility) && buttonRects[i].contains(x, y)) return i
        }
        return -1
    }

    private fun stickAt(x: Float, y: Float): Int {
        for (i in layout.sticks.indices.reversed()) {
            val s = layout.sticks[i]
            val c = stickCenters[i]
            if (shown(s.layer, s.visibility) && hypot(x - c[0], y - c[1]) <= c[2] * 1.15f) return i
        }
        return -1
    }

    private fun pressButton(i: Int) {
        val b = layout.buttons[i]
        pressed[i]++
        vibrate()
        b.press.forEach(::down)
        if (b.toggle.isNotEmpty()) {
            toggled[i] = !toggled[i]
            if (toggled[i]) b.toggle.forEach(::down) else b.toggle.forEach(::up)
        }
        invalidate()
    }

    private fun releaseButton(i: Int, tapped: Boolean) {
        val b = layout.buttons[i]
        if (pressed[i] > 0) pressed[i]--
        b.press.forEach(::up)
        if (tapped) b.tap.forEach(::tap)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = e.actionIndex
                if (!pointerDown(e.getPointerId(index), e.getX(index), e.getY(index), e.eventTime)) return false
            }
            MotionEvent.ACTION_MOVE -> for (index in 0 until e.pointerCount) pointerMove(e.getPointerId(index), e.getX(index), e.getY(index))
            MotionEvent.ACTION_POINTER_UP -> {
                val index = e.actionIndex
                pointerUp(e.getPointerId(index), e.eventTime, false)
            }
            MotionEvent.ACTION_UP -> pointerUp(e.getPointerId(e.actionIndex), e.eventTime, false)
            MotionEvent.ACTION_CANCEL -> roles.keys.toList().forEach { pointerUp(it, e.eventTime, true) }
        }
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float, time: Long): Boolean {
        if (showHandle && !editMode && menuRect.contains(x, y)) {
            roles[id] = Role.Handle(x, y, x - menuRect.left, y - menuRect.top)
            return true
        }
        if (editMode) {
            val b = buttonAt(x, y)
            val target: Selection? = if (b >= 0) Selection.Button(b) else stickAt(x, y).takeIf { it >= 0 }?.let { Selection.Stick(it) }
            select(target)
            if (target == null) {
                roles[id] = Role.Ignore
                return true
            }
            val (ox, oy) = when (target) {
                is Selection.Button -> x - buttonRects[target.index].left to y - buttonRects[target.index].top
                is Selection.Stick -> x - (stickCenters[target.index][0] - stickCenters[target.index][2]) to y - (stickCenters[target.index][1] - stickCenters[target.index][2])
            }
            roles[id] = Role.Drag(target, ox, oy)
            return true
        }
        val b = buttonAt(x, y)
        if (b >= 0) {
            roles[id] = Role.Btn(b, x, y)
            pressButton(b)
            return true
        }
        val s = stickAt(x, y)
        if (s >= 0) {
            roles[id] = Role.Stick(s)
            stickDown(s, x, y, time)
            return true
        }
        if (captured) {
            if (settings.hotbarTouch) {
                val slot = HorizonBridge.hotbarSlot(x, y)
                if (slot >= 0) {
                    HorizonBridge.command("select ${slot + 1}")
                    vibrate()
                    roles[id] = Role.Ignore
                    return true
                }
            }
            val look = Role.Look(x, y, x, y, time)
            look.longPress = Runnable {
                if (!look.moved && roles[id] === look) {
                    look.mining = true
                    holdMouse(0, true)
                }
            }.also { uiHandler.postDelayed(it, settings.longPressMs.toLong()) }
            roles[id] = look
            return true
        }
        if (!virtualMouse) {
            // The window stays touchable so the buttons above work in menus too; everything else goes to
            // the engine, which handles menu taps, drags and sliders itself.
            roles[id] = Role.Forward(x, y)
            HorizonBridge.touch(id, TOUCH_DOWN, x, y)
            return true
        }
        val first = roles.values.firstOrNull { it is Role.Mouse } as Role.Mouse?
        if (first != null) {
            first.consumed = true
            first.longPress?.let(uiHandler::removeCallbacks)
            roles[id] = Role.Scroll(y, time)
            return true
        }
        val m = Role.Mouse(x, y, x, y, time)
        m.longPress = Runnable {
            if (!m.moved && !m.consumed && roles[id] === m) {
                m.dragging = true
                vibrate()
                HorizonBridge.button(0, true)
            }
        }.also { uiHandler.postDelayed(it, settings.longPressMs.toLong() + 100) }
        roles[id] = m
        return true
    }

    private var guideX: Float? = null
    private var guideY: Float? = null

    private fun snap(target: Selection, left: Float, top: Float, w: Float, h: Float): Pair<Float, Float> {
        if (!settings.snapEnabled) {
            guideX = null
            guideY = null
            return left to top
        }
        val threshold = settings.snapDp * density
        val xs = mutableListOf(0f, width.toFloat())
        val ys = mutableListOf(0f, height.toFloat())
        layout.buttons.indices.forEach { i ->
            if (target == Selection.Button(i) || !shown(layout.buttons[i].layer, layout.buttons[i].visibility)) return@forEach
            val r = buttonRects[i]
            xs += listOf(r.left, r.right, r.centerX())
            ys += listOf(r.top, r.bottom, r.centerY())
        }
        layout.sticks.indices.forEach { i ->
            if (target == Selection.Stick(i) || !shown(layout.sticks[i].layer, layout.sticks[i].visibility)) return@forEach
            val c = stickCenters[i]
            xs += listOf(c[0] - c[2], c[0] + c[2], c[0])
            ys += listOf(c[1] - c[2], c[1] + c[2], c[1])
        }
        fun best(lines: List<Float>, start: Float, size: Float): Pair<Float, Float?> {
            var delta = 0f
            var line: Float? = null
            var distance = threshold
            for (l in lines) for (edge in floatArrayOf(start, start + size, start + size / 2f)) {
                val d = l - edge
                if (kotlin.math.abs(d) < distance) {
                    distance = kotlin.math.abs(d)
                    delta = d
                    line = l
                }
            }
            return delta to line
        }
        val (dx, lx) = best(xs, left, w)
        val (dy, ly) = best(ys, top, h)
        guideX = lx
        guideY = ly
        return (left + dx) to (top + dy)
    }

    private fun moveTo(target: Selection, rawLeft: Float, rawTop: Float) {
        val w = width
        val h = height
        when (target) {
            is Selection.Button -> {
                val b = layout.buttons[target.index]
                val (bw, bh) = buttonSize(b, w, h)
                val (left, top) = snap(target, rawLeft, rawTop, bw, bh)
                val nx = if (w - bw > 0f) (left / (w - bw)).coerceIn(0f, 1f) else 0f
                val ny = if (h - bh > 0f) (top / (h - bh)).coerceIn(0f, 1f) else 0f
                layout = layout.copy(buttons = layout.buttons.toMutableList().also { it[target.index] = b.copy(x = nx, y = ny) })
            }
            is Selection.Stick -> {
                val s = layout.sticks[target.index]
                val size = stickSize(s, w, h)
                val (left, top) = snap(target, rawLeft, rawTop, size, size)
                val nx = if (w - size > 0f) (left / (w - size)).coerceIn(0f, 1f) else 0f
                val ny = if (h - size > 0f) (top / (h - size)).coerceIn(0f, 1f) else 0f
                layout = layout.copy(sticks = layout.sticks.toMutableList().also { it[target.index] = s.copy(x = nx, y = ny) })
            }
        }
        relayout()
        onLayoutEdited?.invoke()
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        when (val role = roles[id]) {
            is Role.Handle -> {
                if (!role.moved && hypot(x - role.startX, y - role.startY) > 8f * density) role.moved = true
                if (role.moved) {
                    val mw = menuRect.width()
                    val mh = menuRect.height()
                    val left = (x - role.offX).coerceIn(0f, width - mw)
                    val top = (y - role.offY).coerceIn(0f, height - mh)
                    menuRect.set(left, top, left + mw, top + mh)
                    invalidate()
                }
            }
            is Role.Drag -> moveTo(role.target, x - role.offX, y - role.offY)
            is Role.Btn -> {
                val current = layout.buttons[role.index]
                if (current.penetrable && captured) look(x - role.lastX, y - role.lastY)
                role.lastX = x
                role.lastY = y
                val target = buttonAt(x, y)
                if (target >= 0 && target != role.index && (current.swipe || layout.buttons[target].swipe)) {
                    releaseButton(role.index, false)
                    role.index = target
                    pressButton(target)
                }
            }
            is Role.Stick -> stickMove(role.index, x, y)
            is Role.Forward -> {
                role.lastX = x
                role.lastY = y
                HorizonBridge.touch(id, TOUCH_MOVE, x, y)
            }
            is Role.Look -> {
                val dx = x - role.lastX
                val dy = y - role.lastY
                role.lastX = x
                role.lastY = y
                if (!role.moved && hypot(x - role.startX, y - role.startY) > 10f * density) {
                    role.moved = true
                    if (!role.mining) role.longPress?.let(uiHandler::removeCallbacks)
                }
                if (role.moved) look(dx, dy)
            }
            is Role.Mouse -> {
                val dx = x - role.lastX
                val dy = y - role.lastY
                role.lastX = x
                role.lastY = y
                if (!role.moved && hypot(x - role.startX, y - role.startY) > 8f * density) {
                    role.moved = true
                    if (!role.dragging) role.longPress?.let(uiHandler::removeCallbacks)
                }
                if (role.moved && !role.consumed) {
                    cursorX = (cursorX + dx * settings.mouseSpeed).coerceIn(0f, width - 1f)
                    cursorY = (cursorY + dy * settings.mouseSpeed).coerceIn(0f, height - 1f)
                    HorizonBridge.cursor(cursorX, cursorY)
                    invalidate()
                }
            }
            is Role.Scroll -> {
                val dy = y - role.lastY
                role.lastY = y
                role.travelled += dy
                val step = 24f * density
                while (role.travelled >= step) {
                    HorizonBridge.wheel(1f)
                    role.travelled -= step
                    role.scrolled = true
                }
                while (role.travelled <= -step) {
                    HorizonBridge.wheel(-1f)
                    role.travelled += step
                    role.scrolled = true
                }
            }
            else -> {}
        }
    }

    private fun look(dx: Float, dy: Float) {
        if (dx != 0f || dy != 0f) HorizonBridge.look(dx * settings.lookSensitivity, dy * settings.lookSensitivity)
    }

    private fun pointerUp(id: Int, time: Long, cancel: Boolean) {
        when (val role = roles.remove(id)) {
            is Role.Handle -> {
                if (role.moved) {
                    val w = width - menuRect.width()
                    val h = height - menuRect.height()
                    settings.menuX = if (w > 0f) (menuRect.left / w).coerceIn(0f, 1f) else 0.5f
                    settings.menuY = if (h > 0f) (menuRect.top / h).coerceIn(0f, 1f) else 0f
                } else if (!cancel) {
                    host.openMenu()
                }
            }
            is Role.Drag -> {
                guideX = null
                guideY = null
                invalidate()
            }
            is Role.Btn -> releaseButton(role.index, !cancel)
            is Role.Stick -> stickUp(role.index)
            is Role.Forward -> HorizonBridge.touch(id, if (cancel) TOUCH_CANCEL else TOUCH_UP, role.lastX, role.lastY)
            is Role.Look -> {
                role.longPress?.let(uiHandler::removeCallbacks)
                if (role.mining) holdMouse(0, false)
                else if (!cancel && settings.tapToUse && !role.moved && time - role.downTime < 300) tap(Action.Mouse(1))
            }
            is Role.Mouse -> {
                role.longPress?.let(uiHandler::removeCallbacks)
                if (role.dragging) HorizonBridge.button(0, false)
                else if (!cancel && !role.consumed && !role.moved && settings.tapToClick && time - role.downTime < 300) {
                    HorizonBridge.button(0, true)
                    HorizonBridge.button(0, false)
                }
            }
            is Role.Scroll -> if (!cancel && !role.scrolled && time - role.downTime < 300) {
                HorizonBridge.button(1, true)
                HorizonBridge.button(1, false)
            }
            else -> {}
        }
    }

    private fun stickDown(i: Int, x: Float, y: Float, time: Long) {
        val s = layout.sticks[i]
        if (stickLocked[i]) {
            stickLocked[i] = false
            s.lock.forEach(::up)
            setStickActions(i, emptyList())
        }
        if (s.doubleTap.isNotEmpty() && time - stickLastTap[i] < 300) {
            stickSneak[i] = !stickSneak[i]
            if (stickSneak[i]) s.doubleTap.forEach(::down) else s.doubleTap.forEach(::up)
            vibrate()
        }
        stickLastTap[i] = time
        stickMove(i, x, y)
    }

    private fun setStickActions(i: Int, next: List<Action>) {
        val old = stickHeld[i]
        old.filter { it !in next }.forEach(::up)
        next.filter { it !in old }.forEach(::down)
        stickHeld[i] = next
    }

    private fun stickMove(i: Int, x: Float, y: Float) {
        val s = layout.sticks[i]
        val c = stickCenters[i]
        val radius = c[2]
        val dx = x - c[0]
        val dy = y - c[1]
        val dist = hypot(dx, dy)
        val ratio = if (radius > 0f) dist / radius else 0f
        val k = if (dist > radius && dist > 0f) radius / dist else 1f
        stickKnob[i][0] = dx * k
        stickKnob[i][1] = dy * k
        val dead = min(s.deadZone, 0.35f)
        val dir = if (ratio < dead) -1 else {
            val deg = (Math.toDegrees(atan2(-dy, dx).toDouble()) + 360.0) % 360.0
            (((deg + 22.5) / 45.0).toInt()) % 8
        }
        setStickActions(i, if (dir < 0) emptyList() else s.directions[dir].orEmpty())
        val armed = s.canLock && dir == ControlLayout.NORTH && dist >= radius * (1f + s.lockThreshold)
        if (armed != stickArmed[i]) {
            stickArmed[i] = armed
            if (armed) vibrate()
        }
        invalidate()
    }

    private fun stickUp(i: Int) {
        val s = layout.sticks[i]
        if (stickArmed[i]) {
            stickArmed[i] = false
            stickLocked[i] = true
            setStickActions(i, s.directions[ControlLayout.NORTH].orEmpty())
            s.lock.forEach(::down)
            stickKnob[i][0] = 0f
            stickKnob[i][1] = -stickCenters[i][2]
        } else {
            setStickActions(i, emptyList())
            stickKnob[i][0] = 0f
            stickKnob[i][1] = 0f
        }
        invalidate()
    }

    private fun alpha(color: Int, extra: Float): Int =
        Color.argb((Color.alpha(color) * extra).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        val opacity = if (editMode) settings.opacity.coerceAtLeast(0.60f) else settings.opacity
        layout.sticks.forEachIndexed { i, s ->
            if (!shown(s.layer, s.visibility)) return@forEachIndexed
            val st = s.style ?: defaultStyle
            val c = stickCenters[i]
            val active = stickHeld[i].isNotEmpty() || stickLocked[i]
            val so = opacity * st.opacity
            fill.color = alpha(if (stickArmed[i] || stickLocked[i]) st.fillPressed else st.fill, so)
            canvas.drawCircle(c[0], c[1], c[2], fill)
            stroke.color = alpha(st.stroke, so)
            stroke.strokeWidth = (st.strokeDp * density).coerceAtLeast(1f)
            canvas.drawCircle(c[0], c[1], c[2], stroke)
            fill.color = alpha(if (active) st.fillPressed else st.stroke, so)
            canvas.drawCircle(c[0] + stickKnob[i][0], c[1] + stickKnob[i][1], c[2] * 0.38f, fill)
            if (stickSneak[i]) {
                stroke.color = alpha(Color.YELLOW, so)
                canvas.drawCircle(c[0], c[1], c[2] * 0.5f, stroke)
            }
            if (editMode && selection == Selection.Stick(i)) {
                stroke.color = SELECTION
                stroke.strokeWidth = 2.5f * density
                canvas.drawCircle(c[0], c[1], c[2] + 3f * density, stroke)
            }
        }
        layout.buttons.forEachIndexed { i, b ->
            if (!shown(b.layer, b.visibility)) return@forEachIndexed
            drawButton(canvas, b, buttonRects[i], pressed[i] > 0 || toggled[i], opacity)
            if (editMode && selection == Selection.Button(i)) {
                stroke.color = SELECTION
                stroke.strokeWidth = 2.5f * density
                val r = buttonRects[i]
                canvas.drawRect(r.left - 3f * density, r.top - 3f * density, r.right + 3f * density, r.bottom + 3f * density, stroke)
            }
        }
        if (editMode) {
            stroke.color = Color.argb(220, 255, 214, 0)
            stroke.strokeWidth = 1.5f * density
            guideX?.let { canvas.drawLine(it, 0f, it, height.toFloat(), stroke) }
            guideY?.let { canvas.drawLine(0f, it, width.toFloat(), it, stroke) }
        }
        if (showHandle && !editMode) {
            val dragging = roles.values.any { it is Role.Handle && it.moved }
            val rad = menuRect.height() / 2f
            fill.color = alpha(Color.argb(if (dragging) 240 else 205, 34, 40, 44), opacity.coerceAtLeast(0.55f))
            canvas.drawRoundRect(menuRect, rad, rad, fill)
            stroke.color = Color.argb(if (dragging) 230 else 140, 139, 224, 255)
            stroke.strokeWidth = 1f * density
            canvas.drawRoundRect(menuRect, rad, rad, stroke)
            menuIcon?.let { icon ->
                val half = menuRect.height() * 0.34f
                iconRect.set(menuRect.centerX() - half, menuRect.centerY() - half, menuRect.centerX() + half, menuRect.centerY() + half)
                iconPaint.colorFilter = PorterDuffColorFilter(if (dragging) Color.WHITE else Color.argb(235, 139, 224, 255), PorterDuff.Mode.SRC_IN)
                canvas.drawBitmap(icon, null, iconRect, iconPaint)
            }
        }
        if (!editMode && !captured && virtualMouse) drawCursor(canvas)
    }

    private fun drawButton(canvas: Canvas, b: ControlButton, r: RectF, active: Boolean, baseOpacity: Float) {
        val st = b.style ?: defaultStyle
        val opacity = baseOpacity * st.opacity
        val radius = if (st.radiusInPx) st.radiusDp else st.radiusDp * density
        val rad = min(radius, min(r.width(), r.height()) / 2f)
        fill.color = alpha(if (active) st.fillPressed else st.fill, opacity)
        canvas.drawRoundRect(r, rad, rad, fill)
        if (st.strokeDp > 0f) {
            stroke.color = alpha(st.stroke, opacity)
            stroke.strokeWidth = st.strokeDp * density
            canvas.drawRoundRect(r, rad, rad, stroke)
        } else if (Color.alpha(st.fill) * opacity < 110f) {
            stroke.color = Color.argb(130, 255, 255, 255)
            stroke.strokeWidth = density
            canvas.drawRoundRect(r, rad, rad, stroke)
        }
        if (b.text.isEmpty()) return
        text.color = alpha(if (active) st.textPressed else st.text, opacity)
        text.textSize = st.textSp * scaledDensity * settings.buttonScale.coerceIn(0.75f, 1.50f)
        val lines = b.text.split('\n')
        val widest = lines.maxOf { text.measureText(it) }
        if (widest > r.width() * 0.92f && widest > 0f) text.textSize *= r.width() * 0.92f / widest
        val lineHeight = text.descent() - text.ascent()
        var baseline = r.centerY() - lineHeight * (lines.size - 1) / 2f - (text.descent() + text.ascent()) / 2f
        for (line in lines) {
            canvas.drawText(line, r.centerX(), baseline, text)
            baseline += lineHeight
        }
    }

    private fun drawCursor(canvas: Canvas) {
        val s = 22f * density * settings.cursorSize
        val drawable = cursorDrawable
        val movie = cursorMovie
        if (drawable != null || movie != null) {
            val iw = (drawable?.intrinsicWidth ?: movie!!.width()).coerceAtLeast(1)
            val ih = (drawable?.intrinsicHeight ?: movie!!.height()).coerceAtLeast(1)
            val h = s * 1.3f
            val w = h * iw / ih
            val left = if (settings.cursorCenter) cursorX - w / 2f else cursorX
            val top = if (settings.cursorCenter) cursorY - h / 2f else cursorY
            if (drawable != null) {
                drawable.setBounds(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())
                drawable.draw(canvas)
            } else if (movie != null) {
                val duration = movie.duration().takeIf { it > 0 } ?: 1000
                @Suppress("DEPRECATION")
                movie.setTime(((SystemClock.uptimeMillis() - movieStart) % duration).toInt())
                canvas.save()
                canvas.translate(left, top)
                canvas.scale(w / iw, h / ih)
                @Suppress("DEPRECATION")
                movie.draw(canvas, 0f, 0f)
                canvas.restore()
                postInvalidateOnAnimation()
            }
            return
        }
        cursorPath.reset()
        cursorPath.moveTo(cursorX, cursorY)
        cursorPath.lineTo(cursorX, cursorY + s)
        cursorPath.lineTo(cursorX + s * 0.28f, cursorY + s * 0.74f)
        cursorPath.lineTo(cursorX + s * 0.48f, cursorY + s * 1.10f)
        cursorPath.lineTo(cursorX + s * 0.62f, cursorY + s * 1.03f)
        cursorPath.lineTo(cursorX + s * 0.43f, cursorY + s * 0.68f)
        cursorPath.lineTo(cursorX + s * 0.78f, cursorY + s * 0.68f)
        cursorPath.close()
        fill.color = Color.WHITE
        canvas.drawPath(cursorPath, fill)
        stroke.color = Color.BLACK
        stroke.strokeWidth = 1.5f * density
        canvas.drawPath(cursorPath, stroke)
    }
}

/** Outline of the selected button in the editor (the Zalith editor's teal). */
private val SELECTION = 0xFF80CBC4.toInt()
