package dev.horizon.android.controls

import android.content.Context
import android.view.KeyEvent
import dev.horizon.android.R

object ActionCatalog {
    private val keyNames: List<Pair<String, Int>> = buildList {
        for (c in 'A'..'Z') add(c.toString() to KeyEvent.KEYCODE_A + (c - 'A'))
        for (d in 0..9) add(d.toString() to KeyEvent.KEYCODE_0 + d)
        for (f in 1..12) add("F$f" to KeyEvent.KEYCODE_F1 + f - 1)
        add("Space" to KeyEvent.KEYCODE_SPACE)
        add("Left Shift" to KeyEvent.KEYCODE_SHIFT_LEFT)
        add("Left Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT)
        add("Left Alt" to KeyEvent.KEYCODE_ALT_LEFT)
        add("Right Shift" to KeyEvent.KEYCODE_SHIFT_RIGHT)
        add("Right Ctrl" to KeyEvent.KEYCODE_CTRL_RIGHT)
        add("Right Alt" to KeyEvent.KEYCODE_ALT_RIGHT)
        add("Tab" to KeyEvent.KEYCODE_TAB)
        add("Esc" to KeyEvent.KEYCODE_ESCAPE)
        add("Enter" to KeyEvent.KEYCODE_ENTER)
        add("Backspace" to KeyEvent.KEYCODE_DEL)
        add("Delete" to KeyEvent.KEYCODE_FORWARD_DEL)
        add("Insert" to KeyEvent.KEYCODE_INSERT)
        add("Home" to KeyEvent.KEYCODE_MOVE_HOME)
        add("End" to KeyEvent.KEYCODE_MOVE_END)
        add("Page Up" to KeyEvent.KEYCODE_PAGE_UP)
        add("Page Down" to KeyEvent.KEYCODE_PAGE_DOWN)
        add("↑" to KeyEvent.KEYCODE_DPAD_UP)
        add("↓" to KeyEvent.KEYCODE_DPAD_DOWN)
        add("←" to KeyEvent.KEYCODE_DPAD_LEFT)
        add("→" to KeyEvent.KEYCODE_DPAD_RIGHT)
        add("-" to KeyEvent.KEYCODE_MINUS)
        add("=" to KeyEvent.KEYCODE_EQUALS)
        add("[" to KeyEvent.KEYCODE_LEFT_BRACKET)
        add("]" to KeyEvent.KEYCODE_RIGHT_BRACKET)
        add(";" to KeyEvent.KEYCODE_SEMICOLON)
        add("'" to KeyEvent.KEYCODE_APOSTROPHE)
        add("," to KeyEvent.KEYCODE_COMMA)
        add("." to KeyEvent.KEYCODE_PERIOD)
        add("/" to KeyEvent.KEYCODE_SLASH)
        add("\\" to KeyEvent.KEYCODE_BACKSLASH)
        add("`" to KeyEvent.KEYCODE_GRAVE)
        add("Caps Lock" to KeyEvent.KEYCODE_CAPS_LOCK)
    }

    fun all(context: Context): List<Pair<String, Action>> = listOf(
        context.getString(R.string.action_mouse_left) to Action.Mouse(0),
        context.getString(R.string.action_mouse_right) to Action.Mouse(1),
        context.getString(R.string.action_mouse_middle) to Action.Mouse(2),
        context.getString(R.string.action_scroll_up) to Action.Scroll(1f, false),
        context.getString(R.string.action_scroll_down) to Action.Scroll(-1f, false),
        context.getString(R.string.action_keyboard) to Action.Keyboard,
        context.getString(R.string.action_menu) to Action.Menu,
        context.getString(R.string.action_toggle_mouse) to Action.ToggleMouse,
    ) + keyNames.map { (name, code) -> name to Action.Key(code) }

    fun label(context: Context, action: Action): String = when (action) {
        is Action.Key -> keyNames.firstOrNull { it.second == action.code }?.first ?: KeyEvent.keyCodeToString(action.code).removePrefix("KEYCODE_")
        is Action.Text -> "\"${action.text}\""
        is Action.Layer -> context.getString(R.string.action_layer)
        is Action.Scroll -> context.getString(if (action.lines > 0) R.string.action_scroll_up else R.string.action_scroll_down)
        else -> all(context).firstOrNull { it.second == action }?.first ?: action.toString()
    }
}
