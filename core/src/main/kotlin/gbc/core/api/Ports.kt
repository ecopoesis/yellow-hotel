package gbc.core.api

/** Receives bytes the game shifts out of the link port (Blargg tests report through this). */
fun interface SerialOut {
    fun byte(value: Int)
}

/**
 * Host-side adapters the emulation core writes into while stepping. These are
 * not part of the emulated state — they are the output edges of the system.
 */
class Ports(
    val serial: SerialOut = SerialOut { },
) {
    companion object {
        val NONE = Ports()
    }
}
