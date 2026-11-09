package dev.horizon.android.controls

import android.view.KeyEvent

object Keycodes {
    private val named: Map<String, Int> = buildMap {
        for (c in 'A'..'Z') put(c.toString(), KeyEvent.KEYCODE_A + (c - 'A'))
        for (d in 0..9) put(d.toString(), KeyEvent.KEYCODE_0 + d)
        for (f in 1..12) put("F$f", KeyEvent.KEYCODE_F1 + f - 1)
        for (d in 0..9) put("KP_$d", KeyEvent.KEYCODE_NUMPAD_0 + d)
        put("SPACE", KeyEvent.KEYCODE_SPACE)
        put("APOSTROPHE", KeyEvent.KEYCODE_APOSTROPHE)
        put("COMMA", KeyEvent.KEYCODE_COMMA)
        put("MINUS", KeyEvent.KEYCODE_MINUS)
        put("PERIOD", KeyEvent.KEYCODE_PERIOD)
        put("SLASH", KeyEvent.KEYCODE_SLASH)
        put("SEMICOLON", KeyEvent.KEYCODE_SEMICOLON)
        put("EQUAL", KeyEvent.KEYCODE_EQUALS)
        put("LEFT_BRACKET", KeyEvent.KEYCODE_LEFT_BRACKET)
        put("BACKSLASH", KeyEvent.KEYCODE_BACKSLASH)
        put("RIGHT_BRACKET", KeyEvent.KEYCODE_RIGHT_BRACKET)
        put("GRAVE_ACCENT", KeyEvent.KEYCODE_GRAVE)
        put("ESCAPE", KeyEvent.KEYCODE_ESCAPE)
        put("ENTER", KeyEvent.KEYCODE_ENTER)
        put("TAB", KeyEvent.KEYCODE_TAB)
        put("BACKSPACE", KeyEvent.KEYCODE_DEL)
        put("INSERT", KeyEvent.KEYCODE_INSERT)
        put("DELETE", KeyEvent.KEYCODE_FORWARD_DEL)
        put("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT)
        put("LEFT", KeyEvent.KEYCODE_DPAD_LEFT)
        put("DOWN", KeyEvent.KEYCODE_DPAD_DOWN)
        put("UP", KeyEvent.KEYCODE_DPAD_UP)
        put("PAGE_UP", KeyEvent.KEYCODE_PAGE_UP)
        put("PAGE_DOWN", KeyEvent.KEYCODE_PAGE_DOWN)
        put("HOME", KeyEvent.KEYCODE_MOVE_HOME)
        put("END", KeyEvent.KEYCODE_MOVE_END)
        put("CAPS_LOCK", KeyEvent.KEYCODE_CAPS_LOCK)
        put("SCROLL_LOCK", KeyEvent.KEYCODE_SCROLL_LOCK)
        put("NUM_LOCK", KeyEvent.KEYCODE_NUM_LOCK)
        put("PRINT_SCREEN", KeyEvent.KEYCODE_SYSRQ)
        put("PAUSE", KeyEvent.KEYCODE_BREAK)
        put("KP_DECIMAL", KeyEvent.KEYCODE_NUMPAD_DOT)
        put("KP_DIVIDE", KeyEvent.KEYCODE_NUMPAD_DIVIDE)
        put("KP_MULTIPLY", KeyEvent.KEYCODE_NUMPAD_MULTIPLY)
        put("KP_SUBTRACT", KeyEvent.KEYCODE_NUMPAD_SUBTRACT)
        put("KP_ADD", KeyEvent.KEYCODE_NUMPAD_ADD)
        put("KP_ENTER", KeyEvent.KEYCODE_NUMPAD_ENTER)
        put("KP_EQUAL", KeyEvent.KEYCODE_NUMPAD_EQUALS)
        put("LEFT_SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT)
        put("LEFT_CONTROL", KeyEvent.KEYCODE_CTRL_LEFT)
        put("LEFT_ALT", KeyEvent.KEYCODE_ALT_LEFT)
        put("LEFT_SUPER", KeyEvent.KEYCODE_META_LEFT)
        put("RIGHT_SHIFT", KeyEvent.KEYCODE_SHIFT_RIGHT)
        put("RIGHT_CONTROL", KeyEvent.KEYCODE_CTRL_RIGHT)
        put("RIGHT_ALT", KeyEvent.KEYCODE_ALT_RIGHT)
        put("RIGHT_SUPER", KeyEvent.KEYCODE_META_RIGHT)
        put("MENU", KeyEvent.KEYCODE_MENU)
    }

    private val evdev: Map<Int, Int> = buildMap {
        val rows = listOf(
            2 to "1", 3 to "2", 4 to "3", 5 to "4", 6 to "5", 7 to "6", 8 to "7", 9 to "8", 10 to "9", 11 to "0",
            1 to "ESCAPE", 12 to "MINUS", 13 to "EQUAL", 14 to "BACKSPACE", 15 to "TAB",
            16 to "Q", 17 to "W", 18 to "E", 19 to "R", 20 to "T", 21 to "Y", 22 to "U", 23 to "I", 24 to "O", 25 to "P",
            26 to "LEFT_BRACKET", 27 to "RIGHT_BRACKET", 28 to "ENTER", 29 to "LEFT_CONTROL",
            30 to "A", 31 to "S", 32 to "D", 33 to "F", 34 to "G", 35 to "H", 36 to "J", 37 to "K", 38 to "L",
            39 to "SEMICOLON", 40 to "APOSTROPHE", 41 to "GRAVE_ACCENT", 42 to "LEFT_SHIFT", 43 to "BACKSLASH",
            44 to "Z", 45 to "X", 46 to "C", 47 to "V", 48 to "B", 49 to "N", 50 to "M",
            51 to "COMMA", 52 to "PERIOD", 53 to "SLASH", 54 to "RIGHT_SHIFT", 55 to "KP_MULTIPLY", 56 to "LEFT_ALT",
            57 to "SPACE", 58 to "CAPS_LOCK",
            59 to "F1", 60 to "F2", 61 to "F3", 62 to "F4", 63 to "F5", 64 to "F6", 65 to "F7", 66 to "F8", 67 to "F9", 68 to "F10",
            69 to "NUM_LOCK", 70 to "SCROLL_LOCK",
            71 to "KP_7", 72 to "KP_8", 73 to "KP_9", 74 to "KP_SUBTRACT", 75 to "KP_4", 76 to "KP_5", 77 to "KP_6",
            78 to "KP_ADD", 79 to "KP_1", 80 to "KP_2", 81 to "KP_3", 82 to "KP_0", 83 to "KP_DECIMAL",
            87 to "F11", 88 to "F12", 96 to "KP_ENTER", 97 to "RIGHT_CONTROL", 98 to "KP_DIVIDE", 99 to "PRINT_SCREEN",
            100 to "RIGHT_ALT", 102 to "HOME", 103 to "UP", 104 to "PAGE_UP", 105 to "LEFT", 106 to "RIGHT",
            107 to "END", 108 to "DOWN", 109 to "PAGE_DOWN", 110 to "INSERT", 111 to "DELETE", 117 to "KP_EQUAL",
            119 to "PAUSE", 125 to "LEFT_SUPER", 126 to "RIGHT_SUPER",
        )
        rows.forEach { (code, name) -> named[name]?.let { put(code, it) } }
    }

    private val reverse: Map<Int, String> by lazy { named.entries.associate { it.value to it.key } }

    /** The engine's name for an Android key code (`key.keyboard.left.shift`), or null when it has none. */
    fun engineName(code: Int): String? {
        val glfw = reverse[code]?.lowercase() ?: return null
        val tail = when {
            glfw.startsWith("kp_") -> "keypad." + glfw.removePrefix("kp_").replace('_', '.')
            glfw.endsWith("_super") -> glfw.removeSuffix("_super") + ".win"
            else -> glfw.replace('_', '.')
        }
        return "key.keyboard.$tail"
    }

    private val glfwCodes: Map<Int, String> by lazy {
        buildMap {
            for (c in 'A'..'Z') put(c.code, c.toString())
            for (d in 0..9) {
                put(48 + d, d.toString())
                put(320 + d, "KP_$d")
            }
            for (f in 1..12) put(289 + f, "F$f")
            put(32, "SPACE"); put(39, "APOSTROPHE"); put(44, "COMMA"); put(45, "MINUS"); put(46, "PERIOD")
            put(47, "SLASH"); put(59, "SEMICOLON"); put(61, "EQUAL"); put(91, "LEFT_BRACKET"); put(92, "BACKSLASH")
            put(93, "RIGHT_BRACKET"); put(96, "GRAVE_ACCENT")
            put(256, "ESCAPE"); put(257, "ENTER"); put(258, "TAB"); put(259, "BACKSPACE"); put(260, "INSERT")
            put(261, "DELETE"); put(262, "RIGHT"); put(263, "LEFT"); put(264, "DOWN"); put(265, "UP")
            put(266, "PAGE_UP"); put(267, "PAGE_DOWN"); put(268, "HOME"); put(269, "END")
            put(280, "CAPS_LOCK"); put(281, "SCROLL_LOCK"); put(282, "NUM_LOCK"); put(283, "PRINT_SCREEN"); put(284, "PAUSE")
            put(330, "KP_DECIMAL"); put(331, "KP_DIVIDE"); put(332, "KP_MULTIPLY"); put(333, "KP_SUBTRACT")
            put(334, "KP_ADD"); put(335, "KP_ENTER"); put(336, "KP_EQUAL")
            put(340, "LEFT_SHIFT"); put(341, "LEFT_CONTROL"); put(342, "LEFT_ALT"); put(343, "LEFT_SUPER")
            put(344, "RIGHT_SHIFT"); put(345, "RIGHT_CONTROL"); put(346, "RIGHT_ALT"); put(347, "RIGHT_SUPER"); put(348, "MENU")
        }
    }

    /** The Android key code for a GLFW key code (as Pojav / Zalith control maps store them), or null. */
    fun fromGlfwCode(code: Int): Int? = glfwCodes[code]?.let { named[it] }

    fun fromGlfwName(name: String): Int? = named[name.removePrefix("GLFW_KEY_")]

    fun fromEvdev(code: Int): Int? = evdev[code]
}
