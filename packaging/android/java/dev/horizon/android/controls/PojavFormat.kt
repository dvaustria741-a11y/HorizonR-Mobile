package dev.horizon.android.controls

import android.content.res.Resources
import android.graphics.Color
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * ZalithLauncher / PojavLauncher control maps: `mControlDataList`, `mDrawerDataList` and
 * `mJoystickDataList`, with positions as `dynamicX` / `dynamicY` expressions (`${screen_width}`, `px(..)`).
 * Positions are evaluated for this screen once, then kept as fractions like every other layout here.
 * Drawers become a button that toggles a layer holding the drawer's buttons.
 */
internal object PojavFormat {
    private const val MAIN = "zalith"
    private const val GUI = "zalith_gui"
    private const val MARGIN_DP = 2f
    private val VARIABLE = Regex("\\$\\{(\\w+)\\}")

    private class Screen(val width: Float, val height: Float, val density: Float)

    fun parse(json: JSONObject, fallbackName: String): ControlLayout {
        val dm = Resources.getSystem().displayMetrics
        val screen = Screen(
            maxOf(dm.widthPixels, dm.heightPixels).toFloat(),
            minOf(dm.widthPixels, dm.heightPixels).toFloat(),
            dm.density,
        )
        val layers = mutableListOf(ControlLayer(MAIN, true, Visibility.ALWAYS), ControlLayer(GUI, true, Visibility.ALWAYS))
        val buttons = mutableListOf<ControlButton>()
        val sticks = mutableListOf<ControlStick>()
        var n = 0

        for (c in json.objects("mControlDataList")) {
            val gui = c.optJSONArray("keycodes")?.firstInt() == -2
            buttons += button(c, "zalith-${n++}", if (gui) GUI else MAIN, null, screen)
        }
        for (d in json.objects("mDrawerDataList")) {
            val props = d.optJSONObject("properties") ?: continue
            val layerId = "zalith_drawer_${n++}"
            layers += ControlLayer(layerId, false, Visibility.ALWAYS)
            val dw = props.optDouble("width", 50.0).toFloat() * screen.density
            val dh = props.optDouble("height", 50.0).toFloat() * screen.density
            val dx = position(props.optString("dynamicX"), dw, dh, screen)
            val dy = position(props.optString("dynamicY"), dw, dh, screen)
            val toggle = ControlButton(
                id = "zalith-drawer-$n", text = props.optString("name"), layer = MAIN,
                x = fraction(dx, dw, screen.width), y = fraction(dy, dh, screen.height),
                width = Size(props.optDouble("width", 50.0).toFloat()), height = Size(props.optDouble("height", 50.0).toFloat()),
                visibility = visibility(props), press = listOf(Action.Layer(listOf(layerId), Action.LAYER_TOGGLE)),
                style = style(props, screen),
            )
            buttons += toggle
            val orientation = d.optString("orientation", "LEFT")
            val margin = MARGIN_DP * screen.density
            d.objects("buttonProperties").forEachIndexed { i, sub ->
                val at = when (orientation) {
                    "RIGHT" -> (dx + (dw + margin) * (i + 1)) to dy
                    "LEFT" -> (dx - (dw + margin) * (i + 1)) to dy
                    "UP" -> dx to (dy - (dh + margin) * (i + 1))
                    "DOWN" -> dx to (dy + (dh + margin) * (i + 1))
                    else -> null
                }
                buttons += button(sub, "zalith-${n++}", layerId, at, screen)
            }
        }
        for (j in json.objects("mJoystickDataList")) {
            val size = j.optDouble("width", 130.0).toFloat()
            val px = size * screen.density
            sticks += ControlStick(
                id = "zalith-${n++}", layer = MAIN,
                x = fraction(position(j.optString("dynamicX"), px, px, screen), px, screen.width),
                y = fraction(position(j.optString("dynamicY"), px, px, screen), px, screen.height),
                size = Size(size), visibility = visibility(j),
                directions = ControlLayout.wasd(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D),
                lock = listOf(Action.Key(KeyEvent.KEYCODE_CTRL_LEFT)), canLock = j.optBoolean("forwardLock", false),
                lockThreshold = 0.25f, deadZone = 0.15f, style = style(j, screen),
            )
        }
        val info = json.optJSONObject("mControlInfoDataList")?.optString("name").orEmpty()
        val name = info.takeIf { it.isNotBlank() && it != "null" && !it.startsWith("control.") } ?: fallbackName
        return ControlLayout(name, layers, buttons, sticks)
    }

    private fun button(c: JSONObject, id: String, layer: String, at: Pair<Float, Float>?, screen: Screen): ControlButton {
        val wDp = c.optDouble("width", 50.0).toFloat()
        val hDp = c.optDouble("height", 50.0).toFloat()
        val w = wDp * screen.density
        val h = hDp * screen.density
        val x = at?.first ?: position(c.optString("dynamicX"), w, h, screen)
        val y = at?.second ?: position(c.optString("dynamicY"), w, h, screen)
        val actions = actions(c.optJSONArray("keycodes"))
        val toggleable = c.optBoolean("isToggle", false)
        val holdable = actions.filter { it is Action.Key || it is Action.Mouse }
        return ControlButton(
            id = id, text = c.optString("name"), layer = layer,
            x = fraction(x, w, screen.width), y = fraction(y, h, screen.height),
            width = Size(wDp), height = Size(hDp), visibility = visibility(c),
            press = if (toggleable) actions - holdable.toSet() else actions,
            toggle = if (toggleable) holdable else emptyList(),
            swipe = c.optBoolean("isSwipeable", false),
            penetrable = c.optBoolean("passThruEnabled", false),
            style = style(c, screen, wDp, hDp),
        )
    }

    private fun actions(codes: JSONArray?): List<Action> {
        val out = mutableListOf<Action>()
        for (i in 0 until (codes?.length() ?: 0)) {
            val a = when (val code = codes!!.optInt(i)) {
                0 -> null
                -1 -> if (i == 0) Action.Keyboard else null
                -2 -> Action.Layer(listOf(MAIN), Action.LAYER_TOGGLE)
                -3 -> Action.Mouse(0)
                -4 -> Action.Mouse(1)
                -5 -> Action.ToggleMouse
                -6 -> Action.Mouse(2)
                -7 -> Action.Scroll(1f, true)
                -8 -> Action.Scroll(-1f, true)
                -9 -> Action.Menu
                else -> Keycodes.fromGlfwCode(code)?.let { Action.Key(it) }
            }
            if (a != null && a !in out) out += a
        }
        return out
    }

    private fun visibility(c: JSONObject): Visibility {
        val game = c.optBoolean("displayInGame", true)
        val menu = c.optBoolean("displayInMenu", true)
        return when {
            game && !menu -> Visibility.IN_GAME
            menu && !game -> Visibility.IN_MENU
            else -> Visibility.ALWAYS
        }
    }

    private fun style(c: JSONObject, screen: Screen, wDp: Float = 50f, hDp: Float = 50f): Style {
        val opacity = c.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f)
        val bg = c.optInt("bgColor", 0x4D000000)
        val stroke = c.optInt("strokeColor", -1)
        val half = minOf(wDp, hDp) / 2f
        return Style(
            fill = bg,
            fillPressed = Color.argb(maxOf(Color.alpha(bg), 150), 190, 190, 195),
            text = Color.WHITE,
            textPressed = Color.WHITE,
            stroke = stroke,
            // Zalith stores both as a percentage of half the shorter side.
            strokeDp = c.optDouble("strokeWidth", 0.0).toFloat() / 100f * half,
            radiusDp = c.optDouble("cornerRadius", 0.0).toFloat() / 100f * half,
            textSp = 14f,
            opacity = opacity,
        )
    }

    private fun fraction(px: Float, size: Float, screen: Float): Float =
        if (screen - size <= 0f) 0f else (px / (screen - size)).coerceIn(0f, 1f)

    private fun position(expression: String, w: Float, h: Float, screen: Screen): Float {
        if (expression.isBlank()) return 0f
        val vars = mapOf(
            "top" to 0f, "left" to 0f, "right" to screen.width - w, "bottom" to screen.height - h,
            "width" to w, "height" to h, "screen_width" to screen.width, "screen_height" to screen.height,
            "margin" to MARGIN_DP * screen.density, "preferred_scale" to 100f,
        )
        // Android's regex engine (ICU) rejects an unescaped '}' ("Syntax error in regexp pattern"), so escape
        // it. Kept inside runCatching so one bad expression falls back to 0 instead of failing the whole layout.
        return runCatching {
            val text = VARIABLE.replace(expression) { m -> (vars[m.groupValues[1]] ?: 0f).toString() }
            Expression(text, screen.density).parse().toFloat()
        }.getOrDefault(0f)
    }

    private fun JSONObject.objects(name: String): List<JSONObject> =
        optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    private fun JSONArray.firstInt(): Int = if (length() > 0) optInt(0) else 0

    /** `+ - * /`, brackets, unary minus and `px(dp)` / `dp(px)`: what Pojav's dynamic positions use. */
    private class Expression(private val s: String, private val density: Float) {
        private var i = 0

        fun parse(): Double {
            val v = sum()
            skip()
            require(i == s.length) { "Unexpected '${s.getOrNull(i)}'" }
            return v
        }

        private fun skip() {
            while (i < s.length && s[i] == ' ') i++
        }

        private fun sum(): Double {
            var v = product()
            while (true) {
                skip()
                if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                    val op = s[i++]
                    val r = product()
                    v = if (op == '+') v + r else v - r
                } else return v
            }
        }

        private fun product(): Double {
            var v = unary()
            while (true) {
                skip()
                if (i < s.length && (s[i] == '*' || s[i] == '/')) {
                    val op = s[i++]
                    val r = unary()
                    v = if (op == '*') v * r else v / r
                } else return v
            }
        }

        private fun unary(): Double {
            skip()
            if (i < s.length && s[i] == '-') {
                i++
                return -unary()
            }
            if (i < s.length && s[i] == '+') {
                i++
                return unary()
            }
            return atom()
        }

        private fun expect(c: Char) {
            skip()
            require(i < s.length && s[i] == c) { "Expected '$c'" }
            i++
        }

        private fun atom(): Double {
            skip()
            require(i < s.length) { "Unexpected end" }
            if (s[i] == '(') {
                i++
                val v = sum()
                expect(')')
                return v
            }
            if (s[i].isLetter()) {
                val start = i
                while (i < s.length && s[i].isLetter()) i++
                val name = s.substring(start, i)
                expect('(')
                val arg = sum()
                expect(')')
                return when (name) {
                    "px" -> arg * density
                    "dp" -> arg / density
                    else -> throw IllegalArgumentException("Unknown function $name")
                }
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'E' || s[i] == 'e')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            require(i > start) { "Number expected" }
            return s.substring(start, i).toDouble()
        }
    }
}
