package gbc.core.api

/** Receives bytes the game shifts out of the link port (Blargg tests report through this). */
fun interface SerialOut {
    fun byte(value: Int)
}

/** Current host button state as a [Button] mask; sampled once per frame. */
fun interface InputSource {
    fun buttons(): Int
}

/** Bit masks for [InputSource]: low nibble directions, high nibble buttons. */
object Button {
    const val RIGHT = 0x01
    const val LEFT = 0x02
    const val UP = 0x04
    const val DOWN = 0x08
    const val A = 0x10
    const val B = 0x20
    const val SELECT = 0x40
    const val START = 0x80
}

/**
 * Host-side adapters the emulation core talks to while stepping. These are
 * not part of the emulated state — they are the edges of the system.
 */
class Ports(
    val serial: SerialOut = SerialOut { },
    val input: InputSource = InputSource { 0 },
) {
    companion object {
        val NONE = Ports()
    }
}
