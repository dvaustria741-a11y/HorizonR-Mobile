package dev.horizon.android.controls

const val TOUCH_DOWN = 0
const val TOUCH_MOVE = 1
const val TOUCH_UP = 2
const val TOUCH_CANCEL = 3

object HorizonBridge {
    @JvmStatic external fun nativeCommand(line: String)
    @JvmStatic external fun nativeLook(dx: Float, dy: Float)
    @JvmStatic external fun nativeWheel(lines: Float)
    @JvmStatic external fun nativeCursor(x: Float, y: Float)
    @JvmStatic external fun nativeButton(button: Int, down: Boolean)
    @JvmStatic external fun nativeTouch(pointer: Int, phase: Int, x: Float, y: Float)
    @JvmStatic external fun nativeSetArea(x: Float, y: Float, width: Float, height: Float)
    @JvmStatic external fun nativeSetScale(x: Float, y: Float)
    @JvmStatic external fun nativeHotbarSlot(x: Float, y: Float): Int
    @JvmStatic external fun nativeIsCaptured(): Boolean
    @JvmStatic external fun nativeSetActive(on: Boolean)

    fun command(line: String) = runCatching { nativeCommand(line) }
    fun look(dx: Float, dy: Float) = runCatching { nativeLook(dx, dy) }
    fun wheel(lines: Float) = runCatching { nativeWheel(lines) }
    fun cursor(x: Float, y: Float) = runCatching { nativeCursor(x, y) }
    fun button(button: Int, down: Boolean) = runCatching { nativeButton(button, down) }
    /** Hands a finger the overlay did not claim to the engine as a native touch ([TOUCH_DOWN] ...). */
    fun touch(pointer: Int, phase: Int, x: Float, y: Float) = runCatching { nativeTouch(pointer, phase, x, y) }
    /** Where the game's window sits inside the overlay view (the overlay also covers the display cutout). */
    fun setArea(x: Float, y: Float, width: Float, height: Float) = runCatching { nativeSetArea(x, y, width, height) }
    fun setScale(x: Float, y: Float) = runCatching { nativeSetScale(x, y) }
    fun hotbarSlot(x: Float, y: Float): Int = runCatching { nativeHotbarSlot(x, y) }.getOrDefault(-1)
    fun setActive(on: Boolean) = runCatching { nativeSetActive(on) }
    fun isCaptured(): Boolean = runCatching { nativeIsCaptured() }.getOrDefault(false)
}
