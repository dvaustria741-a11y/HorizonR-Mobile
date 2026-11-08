package dev.horizon.android.controls

import android.graphics.Color
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

sealed interface Action {
    data class Key(val code: Int) : Action
    data class Mouse(val button: Int) : Action
    data class Scroll(val lines: Float, val repeat: Boolean) : Action
    data object Keyboard : Action
    data object Menu : Action
    data object ToggleMouse : Action
    data class Text(val text: String) : Action
    data class Layer(val ids: List<String>, val mode: Int) : Action

    companion object {
        const val LAYER_TOGGLE = 0
        const val LAYER_SHOW = 1
        const val LAYER_HIDE = 2
    }
}

enum class Visibility { ALWAYS, IN_GAME, IN_MENU }

data class Size(val dp: Float?, val fraction: Float = 0f, val ofWidth: Boolean = false) {
    fun px(width: Int, height: Int, density: Float): Float =
        dp?.let { it * density } ?: (fraction * if (ofWidth) width else height)
}

data class Style(
    val fill: Int,
    val fillPressed: Int,
    val text: Int,
    val textPressed: Int,
    val stroke: Int,
    val strokeDp: Float,
    val radiusDp: Float,
    val textSp: Float,
    val radiusInPx: Boolean = false,
    /** Whole-button opacity (Zalith's "Button Opacity"), multiplied into every colour when drawing. */
    val opacity: Float = 1f,
)

data class ControlButton(
    val id: String,
    val text: String,
    val layer: String,
    val x: Float,
    val y: Float,
    val width: Size,
    val height: Size,
    val visibility: Visibility,
    val press: List<Action>,
    val tap: List<Action> = emptyList(),
    val toggle: List<Action> = emptyList(),
    val swipe: Boolean = false,
    val penetrable: Boolean = false,
    val interactive: Boolean = true,
    val style: Style? = null,
)

data class ControlStick(
    val id: String,
    val layer: String,
    val x: Float,
    val y: Float,
    val size: Size,
    val visibility: Visibility,
    val directions: Map<Int, List<Action>>,
    val lock: List<Action>,
    val canLock: Boolean,
    val lockThreshold: Float,
    val deadZone: Float,
    val doubleTap: List<Action> = emptyList(),
    val style: Style? = null,
)

data class ControlLayer(val id: String, val visible: Boolean, val visibility: Visibility)

data class ControlLayout(
    val name: String,
    val layers: List<ControlLayer>,
    val buttons: List<ControlButton>,
    val sticks: List<ControlStick>,
) {
    companion object {
        const val EAST = 0
        const val NORTH_EAST = 1
        const val NORTH = 2
        const val NORTH_WEST = 3
        const val WEST = 4
        const val SOUTH_WEST = 5
        const val SOUTH = 6
        const val SOUTH_EAST = 7

        fun parse(text: String, fallbackName: String): ControlLayout {
            val json = JSONObject(text)
            return when {
                json.has("pojarush") -> PojarushFormat.parse(json, fallbackName)
                json.has("mControlDataList") -> PojavFormat.parse(json, fallbackName)
                json.has("editorVersion") -> Zalith.parse(json, fallbackName)
                json.has("viewGroups") -> FoldCraft.parse(json, fallbackName)
                else -> throw IllegalArgumentException("Unknown control layout format")
            }
        }

        fun write(layout: ControlLayout): String = PojarushFormat.write(layout).toString(2)

        fun wasd(up: Int, down: Int, left: Int, right: Int): Map<Int, List<Action>> {
            val u = listOf(Action.Key(up))
            val d = listOf(Action.Key(down))
            val l = listOf(Action.Key(left))
            val r = listOf(Action.Key(right))
            return mapOf(
                NORTH to u, SOUTH to d, WEST to l, EAST to r,
                NORTH_EAST to u + r, NORTH_WEST to u + l, SOUTH_EAST to d + r, SOUTH_WEST to d + l,
            )
        }
    }
}

private fun JSONObject.list(name: String): List<JSONObject> =
    optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

private fun JSONArray?.ints(): List<Int> = this?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()

private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

private object Zalith {
    fun parse(json: JSONObject, fallbackName: String): ControlLayout {
        val styles = json.list("styles").associate { it.optString("uuid") to style(it.optJSONObject("darkStyle") ?: it.optJSONObject("lightStyle")) }
        val layers = mutableListOf<ControlLayer>()
        val buttons = mutableListOf<ControlButton>()
        val sticks = mutableListOf<ControlStick>()
        for (layer in json.list("layers")) {
            val id = layer.optString("uuid")
            layers += ControlLayer(id, !layer.optBoolean("hide", false), visibility(layer.optString("visibilityType")))
            for (b in layer.list("normalButtons")) buttons += button(b, id, styles, true)
            for (b in layer.list("textBoxes")) buttons += button(b, id, styles, false)
            for (s in layer.list("joystickButtons")) sticks += stick(s, id)
        }
        val name = json.optJSONObject("info")?.optJSONObject("name")?.let { translate(it) }?.takeIf { it.isNotBlank() } ?: fallbackName
        return ControlLayout(name, layers, buttons, sticks)
    }

    private fun translate(t: JSONObject): String {
        val locale = Locale.getDefault()
        for (ls in t.list("matchQueue")) {
            val tag = ls.optString("language_tag")
            if (tag.isNotEmpty() && (locale.toLanguageTag().equals(tag, true) || locale.language.equals(tag, true))) return ls.optString("value")
        }
        return t.optString("default")
    }

    private fun visibility(v: String) = when (v) {
        "in_game" -> Visibility.IN_GAME
        "in_menu" -> Visibility.IN_MENU
        else -> Visibility.ALWAYS
    }

    private fun color(v: Long): Int {
        val high = (v ushr 32) and 0xFFFFFFFFL
        return if (high != 0L) high.toInt() else v.toInt()
    }

    private fun style(s: JSONObject?): Style? {
        s ?: return null
        fun alpha(c: Int, a: Double) = Color.argb((Color.alpha(c) * a).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
        val a = s.optDouble("alpha", 1.0)
        val pa = s.optDouble("pressedAlpha", a)
        val radius = s.optJSONObject("borderRadius")?.optDouble("topStart", 0.0) ?: 0.0
        return Style(
            fill = alpha(color(s.optLong("backgroundColor", 0x80000000)), a),
            fillPressed = alpha(color(s.optLong("pressedBackgroundColor", 0xB3000000)), pa),
            text = alpha(color(s.optLong("contentColor", 0xFFFFFFFF)), a),
            textPressed = alpha(color(s.optLong("pressedContentColor", 0xFFFFFFFF)), pa),
            stroke = alpha(color(s.optLong("borderColor", 0xFFFFFFFF)), a),
            strokeDp = s.optDouble("borderWidth", 0.0).toFloat(),
            radiusDp = radius.toFloat(),
            textSp = s.optDouble("fontSize", 14.0).toFloat(),
            radiusInPx = true,
        )
    }

    private fun size(s: JSONObject?): Pair<Size, Size> {
        s ?: return Size(50f) to Size(50f)
        return when (s.optString("type")) {
            "dp" -> Size(s.optDouble("widthDp", 50.0).toFloat()) to Size(s.optDouble("heightDp", 50.0).toFloat())
            "wrap_content" -> Size(s.optDouble("widthDp", 50.0).toFloat()) to Size(s.optDouble("heightDp", 50.0).toFloat())
            else -> Size(null, s.optInt("widthPercentage", 1400) / 10000f, s.optString("widthReference") == "screen_width") to
                Size(null, s.optInt("heightPercentage", 1400) / 10000f, s.optString("heightReference") == "screen_width")
        }
    }

    fun event(e: JSONObject): Action? {
        val key = e.optString("key")
        return when (e.optString("type")) {
            "key", "launcher_event" -> when {
                key.startsWith("GLFW_MOUSE_BUTTON_") -> when (key.removePrefix("GLFW_MOUSE_BUTTON_")) {
                    "LEFT", "1" -> Action.Mouse(0)
                    "RIGHT", "2" -> Action.Mouse(1)
                    "MIDDLE", "3" -> Action.Mouse(2)
                    else -> null
                }
                key == "launcher.event.switch_ime" -> Action.Keyboard
                key == "launcher.event.switch_menu" -> Action.Menu
                key == "launcher.event.scroll_up" -> Action.Scroll(1f, true)
                key == "launcher.event.scroll_up.single" -> Action.Scroll(1f, false)
                key == "launcher.event.scroll_down" -> Action.Scroll(-1f, true)
                key == "launcher.event.scroll_down.single" -> Action.Scroll(-1f, false)
                else -> Keycodes.fromGlfwName(key)?.let { Action.Key(it) }
            }
            "switch_layer" -> Action.Layer(listOf(key), Action.LAYER_TOGGLE)
            "show_layer" -> Action.Layer(listOf(key), Action.LAYER_SHOW)
            "hide_layer" -> Action.Layer(listOf(key), Action.LAYER_HIDE)
            "send_text" -> key.takeIf { it.isNotEmpty() }?.let { Action.Text(it) }
            else -> null
        }
    }

    private fun button(b: JSONObject, layer: String, styles: Map<String, Style?>, interactive: Boolean): ControlButton {
        val pos = b.optJSONObject("position")
        val (w, h) = size(b.optJSONObject("buttonSize"))
        val actions = b.list("clickEvents").mapNotNull { event(it) }
        val toggleable = b.optBoolean("isToggleable", false)
        val holdable = actions.filter { it is Action.Key || it is Action.Mouse }
        return ControlButton(
            id = b.optString("uuid"),
            text = b.optJSONObject("text")?.let { translate(it) }.orEmpty(),
            layer = layer,
            x = (pos?.optInt("x", 5000) ?: 5000) / 10000f,
            y = (pos?.optInt("y", 5000) ?: 5000) / 10000f,
            width = w,
            height = h,
            visibility = visibility(b.optString("visibilityType")),
            press = if (toggleable) actions - holdable.toSet() else actions,
            toggle = if (toggleable) holdable else emptyList(),
            swipe = b.optBoolean("isSwipple", false),
            penetrable = b.optBoolean("isPenetrable", false),
            interactive = interactive,
            style = styles[b.optString("buttonStyle")],
        )
    }

    private fun stick(s: JSONObject, layer: String): ControlStick {
        val pos = s.optJSONObject("position")
        val size = if (s.optString("sizeType") == "dp") Size(s.optDouble("sizeDp", 200.0).toFloat())
        else Size(null, s.optInt("sizePercentage", 2500).coerceAtLeast(2000) / 10000f, false)
        val names = mapOf(
            "east" to ControlLayout.EAST, "north_east" to ControlLayout.NORTH_EAST, "north" to ControlLayout.NORTH,
            "north_west" to ControlLayout.NORTH_WEST, "west" to ControlLayout.WEST, "south_west" to ControlLayout.SOUTH_WEST,
            "south" to ControlLayout.SOUTH, "south_east" to ControlLayout.SOUTH_EAST,
        )
        val directions = s.optJSONObject("directionEvents")?.let { d ->
            names.mapNotNull { (n, i) -> d.optJSONArray(n)?.let { a -> i to (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::event) } } }.toMap()
        } ?: ControlLayout.wasd(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D)
        val lock = if (s.has("lockEvents")) s.list("lockEvents").mapNotNull { event(it) } else listOf(Action.Key(KeyEvent.KEYCODE_CTRL_LEFT))
        return ControlStick(
            id = s.optString("uuid"),
            layer = layer,
            x = (pos?.optInt("x", 5000) ?: 5000) / 10000f,
            y = (pos?.optInt("y", 5000) ?: 5000) / 10000f,
            size = size,
            visibility = visibility(s.optString("visibilityType", "always")),
            directions = directions,
            lock = lock,
            canLock = s.optBoolean("canLock", true),
            lockThreshold = s.optDouble("lockThreshold", 0.3).toFloat(),
            deadZone = s.optDouble("deadZoneRatio", 0.5).toFloat(),
        )
    }
}

private object FoldCraft {
    fun parse(json: JSONObject, fallbackName: String): ControlLayout {
        val styles = json.list("buttonStyles").associate { it.optString("name") to style(it) }
        val stickStyles = json.list("directionStyles").associate { it.optString("name") to (it.optJSONObject("buttonStyle")?.let(::style)) }
        val layers = mutableListOf<ControlLayer>()
        val buttons = mutableListOf<ControlButton>()
        val sticks = mutableListOf<ControlStick>()
        for (group in json.list("viewGroups")) {
            val id = group.optString("id")
            layers += ControlLayer(id, group.optString("visibility", "VISIBLE") != "INVISIBLE", Visibility.ALWAYS)
            val data = group.optJSONObject("viewData") ?: continue
            for (b in data.list("buttonList")) buttons += button(b, id, styles)
            for (d in data.list("directionList")) sticks += stick(d, id, stickStyles)
        }
        return ControlLayout(json.optString("name").ifBlank { fallbackName }, layers, buttons, sticks)
    }

    private fun style(s: JSONObject): Style = Style(
        fill = s.optInt("fillColor", Color.TRANSPARENT),
        fillPressed = s.optInt("fillColorPressed", Color.LTGRAY),
        text = s.optInt("textColor", Color.WHITE),
        textPressed = s.optInt("textColorPressed", Color.WHITE),
        stroke = s.optInt("strokeColor", Color.DKGRAY),
        strokeDp = s.optInt("strokeWidth", 10) / 10f,
        radiusDp = s.optInt("cornerRadius", 100) / 10f,
        textSp = s.optInt("textSize", 12).toFloat(),
    )

    private fun visibility(v: String) = when (v) {
        "IN_GAME" -> Visibility.IN_GAME
        "IN_MENU" -> Visibility.IN_MENU
        else -> Visibility.ALWAYS
    }

    private fun base(info: JSONObject?): Triple<Pair<Float, Float>, Size, Size> {
        info ?: return Triple(0.5f to 0.5f, Size(50f), Size(50f))
        val pos = info.optInt("xPosition", 0) / 1000f to info.optInt("yPosition", 0) / 1000f
        if (info.optString("sizeType") == "ABSOLUTE") {
            return Triple(pos, Size(info.optInt("absoluteWidth", 50).toFloat()), Size(info.optInt("absoluteHeight", 50).toFloat()))
        }
        fun pct(o: JSONObject?) = Size(null, (o?.optInt("size", 50) ?: 50) / 1000f, o?.optString("reference") == "SCREEN_WIDTH")
        return Triple(pos, pct(info.optJSONObject("percentageWidth")), pct(info.optJSONObject("percentageHeight")))
    }

    private fun keys(codes: List<Int>): List<Action> = codes.mapNotNull { code ->
        when (code) {
            1000 -> Action.Mouse(0)
            1001 -> Action.Mouse(2)
            1002 -> Action.Mouse(1)
            1003 -> Action.Scroll(1f, false)
            1004 -> Action.Scroll(-1f, false)
            else -> Keycodes.fromEvdev(code)?.let { Action.Key(it) }
        }
    }

    private fun extras(e: JSONObject?): List<Action> {
        e ?: return emptyList()
        val out = mutableListOf<Action>()
        if (e.optBoolean("openMenu")) out += Action.Menu
        if (e.optBoolean("switchMouseMode")) out += Action.ToggleMouse
        if (e.optBoolean("input") || e.optBoolean("quickInput")) out += Action.Keyboard
        e.optString("outputText").takeIf { it.isNotEmpty() }?.let { out += Action.Text(it) }
        e.optJSONArray("bindViewGroup").strings().filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { out += Action.Layer(it, Action.LAYER_TOGGLE) }
        return out
    }

    private fun button(b: JSONObject, layer: String, styles: Map<String, Style>): ControlButton {
        val (pos, w, h) = base(b.optJSONObject("baseInfo"))
        val event = b.optJSONObject("event")
        val press = event?.optJSONObject("pressEvent")
        val click = event?.optJSONObject("clickEvent")
        val pressKeys = keys(press?.optJSONArray("outputKeycodes").ints())
        val clickKeys = keys(click?.optJSONArray("outputKeycodes").ints())
        val pressKeep = press?.optBoolean("autoKeep") == true
        val clickKeep = click?.optBoolean("autoKeep") == true
        return ControlButton(
            id = b.optString("id"),
            text = b.optString("text"),
            layer = layer,
            x = pos.first,
            y = pos.second,
            width = w,
            height = h,
            visibility = visibility(b.optJSONObject("baseInfo")?.optString("visibilityType").orEmpty()),
            press = (if (pressKeep) emptyList() else pressKeys) + extras(press),
            tap = (if (clickKeep) emptyList() else clickKeys) + extras(click),
            toggle = (if (pressKeep) pressKeys else emptyList()) + (if (clickKeep) clickKeys else emptyList()),
            swipe = event?.optBoolean("swipable", false) == true,
            penetrable = event?.optBoolean("pointerFollow", false) == true,
            style = styles[b.optString("style")],
        )
    }

    private fun stick(d: JSONObject, layer: String, styles: Map<String, Style?>): ControlStick {
        val (pos, w, _) = base(d.optJSONObject("baseInfo"))
        val e = d.optJSONObject("event")
        fun dir(name: String, default: Int) = e?.optJSONArray(name)?.ints()?.let { keys(it) } ?: keys(listOf(default))
        val up = dir("upKeycode", 17)
        val down = dir("downKeycode", 31)
        val left = dir("leftKeycode", 30)
        val right = dir("rightKeycode", 32)
        val sneak = if (e?.optBoolean("sneak", true) != false) keys(listOf(e?.optInt("sneakKeycode", 42) ?: 42)) else emptyList()
        return ControlStick(
            id = d.optString("id"),
            layer = layer,
            x = pos.first,
            y = pos.second,
            size = w,
            visibility = visibility(d.optJSONObject("baseInfo")?.optString("visibilityType").orEmpty()),
            directions = mapOf(
                ControlLayout.NORTH to up, ControlLayout.SOUTH to down, ControlLayout.WEST to left, ControlLayout.EAST to right,
                ControlLayout.NORTH_EAST to up + right, ControlLayout.NORTH_WEST to up + left,
                ControlLayout.SOUTH_EAST to down + right, ControlLayout.SOUTH_WEST to down + left,
            ),
            lock = emptyList(),
            canLock = e?.optBoolean("canLock", false) == true,
            lockThreshold = (e?.optInt("lockThreshold", 30) ?: 30) / 100f,
            deadZone = (e?.optInt("deadZone", 0) ?: 0) / 100f,
            doubleTap = sneak,
            style = styles[d.optString("style")],
        )
    }
}

object DefaultLayout {
    const val ID = "builtin:pojarush"

    fun create(name: String): ControlLayout {
        val play = "play"
        val always = "always"
        fun key(code: Int) = listOf(Action.Key(code))
        fun btn(id: String, text: String, layer: String, x: Float, y: Float, size: Float, vis: Visibility, press: List<Action> = emptyList(), tap: List<Action> = emptyList(), toggle: List<Action> = emptyList(), swipe: Boolean = false) =
            ControlButton(id, text, layer, x, y, Size(size), Size(size), vis, press, tap, toggle, swipe)
        val buttons = listOf(
            btn("jump", "⤒", play, 0.965f, 0.90f, 64f, Visibility.IN_GAME, press = key(KeyEvent.KEYCODE_SPACE), swipe = true),
            btn("sneak", "⇩", play, 0.965f, 0.62f, 56f, Visibility.IN_GAME, toggle = key(KeyEvent.KEYCODE_SHIFT_LEFT)),
            btn("attack", "⚔", play, 0.855f, 0.90f, 56f, Visibility.IN_GAME, press = listOf(Action.Mouse(0)), swipe = true),
            btn("use", "✋", play, 0.855f, 0.62f, 56f, Visibility.IN_GAME, press = listOf(Action.Mouse(1)), swipe = true),
            btn("inventory", "E", play, 0.0f, 0.0f, 42f, Visibility.IN_GAME, tap = key(KeyEvent.KEYCODE_E)),
            btn("drop", "Q", play, 0.065f, 0.0f, 42f, Visibility.IN_GAME, tap = key(KeyEvent.KEYCODE_Q)),
            btn("chat", "T", play, 0.13f, 0.0f, 42f, Visibility.IN_GAME, tap = key(KeyEvent.KEYCODE_T)),
            btn("camera", "F5", play, 0.195f, 0.0f, 42f, Visibility.IN_GAME, tap = key(KeyEvent.KEYCODE_F5)),
            btn("prev", "‹", play, 0.26f, 0.0f, 42f, Visibility.IN_GAME, tap = listOf(Action.Scroll(1f, false))),
            btn("next", "›", play, 0.325f, 0.0f, 42f, Visibility.IN_GAME, tap = listOf(Action.Scroll(-1f, false))),
            btn("keyboard", "⌨", always, 0.935f, 0.0f, 42f, Visibility.ALWAYS, tap = listOf(Action.Keyboard)),
            btn("mouse", "🖱", always, 0.87f, 0.0f, 42f, Visibility.IN_MENU, tap = listOf(Action.ToggleMouse)),
            btn("pause", "⏸", always, 1.0f, 0.0f, 42f, Visibility.ALWAYS, tap = key(KeyEvent.KEYCODE_ESCAPE)),
        )
        val stick = ControlStick(
            id = "move",
            layer = play,
            x = 0.03f,
            y = 0.92f,
            size = Size(null, 0.38f, false),
            visibility = Visibility.IN_GAME,
            directions = ControlLayout.wasd(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D),
            lock = key(KeyEvent.KEYCODE_CTRL_LEFT),
            canLock = true,
            lockThreshold = 0.25f,
            deadZone = 0.15f,
        )
        return ControlLayout(
            name = name,
            layers = listOf(ControlLayer(play, true, Visibility.ALWAYS), ControlLayer(always, true, Visibility.ALWAYS)),
            buttons = buttons,
            sticks = listOf(stick),
        )
    }
}

private object PojarushFormat {
    fun write(l: ControlLayout): JSONObject = JSONObject()
        .put("pojarush", 1)
        .put("name", l.name)
        .put("layers", JSONArray().apply { l.layers.forEach { put(JSONObject().put("id", it.id).put("visible", it.visible).put("visibility", it.visibility.name)) } })
        .put("buttons", JSONArray().apply { l.buttons.forEach { put(button(it)) } })
        .put("sticks", JSONArray().apply { l.sticks.forEach { put(stick(it)) } })

    private fun size(s: Size): JSONObject =
        if (s.dp != null) JSONObject().put("dp", s.dp.toDouble()) else JSONObject().put("fraction", s.fraction.toDouble()).put("ofWidth", s.ofWidth)

    private fun size(o: JSONObject?): Size = when {
        o == null -> Size(50f)
        o.has("dp") -> Size(o.optDouble("dp", 50.0).toFloat())
        else -> Size(null, o.optDouble("fraction", 0.1).toFloat(), o.optBoolean("ofWidth", false))
    }

    private fun style(s: Style?): JSONObject? = s?.let {
        JSONObject().put("fill", it.fill).put("fillPressed", it.fillPressed).put("text", it.text).put("textPressed", it.textPressed)
            .put("stroke", it.stroke).put("strokeDp", it.strokeDp.toDouble()).put("radiusDp", it.radiusDp.toDouble())
            .put("textSp", it.textSp.toDouble()).put("radiusInPx", it.radiusInPx).put("opacity", it.opacity.toDouble())
    }

    private fun style(o: JSONObject?): Style? = o?.let {
        Style(
            it.optInt("fill"), it.optInt("fillPressed"), it.optInt("text", Color.WHITE), it.optInt("textPressed", Color.WHITE),
            it.optInt("stroke"), it.optDouble("strokeDp", 1.0).toFloat(), it.optDouble("radiusDp", 10.0).toFloat(),
            it.optDouble("textSp", 15.0).toFloat(), it.optBoolean("radiusInPx", false),
            it.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
        )
    }

    fun action(a: Action): JSONObject = when (a) {
        is Action.Key -> JSONObject().put("t", "key").put("code", a.code)
        is Action.Mouse -> JSONObject().put("t", "mouse").put("button", a.button)
        is Action.Scroll -> JSONObject().put("t", "scroll").put("lines", a.lines.toDouble()).put("repeat", a.repeat)
        Action.Keyboard -> JSONObject().put("t", "keyboard")
        Action.Menu -> JSONObject().put("t", "menu")
        Action.ToggleMouse -> JSONObject().put("t", "toggle_mouse")
        is Action.Text -> JSONObject().put("t", "text").put("text", a.text)
        is Action.Layer -> JSONObject().put("t", "layer").put("ids", JSONArray(a.ids)).put("mode", a.mode)
    }

    fun action(o: JSONObject): Action? = when (o.optString("t")) {
        "key" -> Action.Key(o.optInt("code"))
        "mouse" -> Action.Mouse(o.optInt("button").coerceIn(0, 2))
        "scroll" -> Action.Scroll(o.optDouble("lines", 1.0).toFloat(), o.optBoolean("repeat", false))
        "keyboard" -> Action.Keyboard
        "menu" -> Action.Menu
        "toggle_mouse" -> Action.ToggleMouse
        "text" -> Action.Text(o.optString("text"))
        "layer" -> Action.Layer(o.optJSONArray("ids").strings(), o.optInt("mode"))
        else -> null
    }

    private fun actions(list: List<Action>) = JSONArray().apply { list.forEach { put(action(it)) } }

    private fun actions(a: JSONArray?): List<Action> =
        a?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::action) } } ?: emptyList()

    private fun button(b: ControlButton): JSONObject = JSONObject()
        .put("id", b.id).put("text", b.text).put("layer", b.layer).put("x", b.x.toDouble()).put("y", b.y.toDouble())
        .put("width", size(b.width)).put("height", size(b.height)).put("visibility", b.visibility.name)
        .put("press", actions(b.press)).put("tap", actions(b.tap)).put("toggle", actions(b.toggle))
        .put("swipe", b.swipe).put("penetrable", b.penetrable).put("interactive", b.interactive)
        .apply { style(b.style)?.let { put("style", it) } }

    private fun stick(s: ControlStick): JSONObject = JSONObject()
        .put("id", s.id).put("layer", s.layer).put("x", s.x.toDouble()).put("y", s.y.toDouble()).put("size", size(s.size))
        .put("visibility", s.visibility.name)
        .put("directions", JSONObject().apply { s.directions.forEach { (k, v) -> put(k.toString(), actions(v)) } })
        .put("lock", actions(s.lock)).put("canLock", s.canLock).put("lockThreshold", s.lockThreshold.toDouble())
        .put("deadZone", s.deadZone.toDouble()).put("doubleTap", actions(s.doubleTap))
        .apply { style(s.style)?.let { put("style", it) } }

    private fun visibility(v: String) = runCatching { Visibility.valueOf(v) }.getOrDefault(Visibility.ALWAYS)

    fun parse(json: JSONObject, fallbackName: String): ControlLayout = ControlLayout(
        name = json.optString("name").ifBlank { fallbackName },
        layers = json.list("layers").map { ControlLayer(it.optString("id"), it.optBoolean("visible", true), visibility(it.optString("visibility"))) },
        buttons = json.list("buttons").map { b ->
            ControlButton(
                id = b.optString("id"), text = b.optString("text"), layer = b.optString("layer"),
                x = b.optDouble("x", 0.5).toFloat(), y = b.optDouble("y", 0.5).toFloat(),
                width = size(b.optJSONObject("width")), height = size(b.optJSONObject("height")),
                visibility = visibility(b.optString("visibility")),
                press = actions(b.optJSONArray("press")), tap = actions(b.optJSONArray("tap")), toggle = actions(b.optJSONArray("toggle")),
                swipe = b.optBoolean("swipe"), penetrable = b.optBoolean("penetrable"), interactive = b.optBoolean("interactive", true),
                style = style(b.optJSONObject("style")),
            )
        },
        sticks = json.list("sticks").map { s ->
            val dirs = s.optJSONObject("directions")
            ControlStick(
                id = s.optString("id"), layer = s.optString("layer"),
                x = s.optDouble("x", 0.1).toFloat(), y = s.optDouble("y", 0.9).toFloat(), size = size(s.optJSONObject("size")),
                visibility = visibility(s.optString("visibility")),
                directions = (0..7).associateWith { actions(dirs?.optJSONArray(it.toString())) },
                lock = actions(s.optJSONArray("lock")), canLock = s.optBoolean("canLock"),
                lockThreshold = s.optDouble("lockThreshold", 0.25).toFloat(), deadZone = s.optDouble("deadZone", 0.15).toFloat(),
                doubleTap = actions(s.optJSONArray("doubleTap")), style = style(s.optJSONObject("style")),
            )
        },
    )
}
