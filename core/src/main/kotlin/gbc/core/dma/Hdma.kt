package gbc.core.dma

/**
 * CGB VRAM DMA. FF51-54 set source/destination; FF55 starts either a general
 * (immediate) transfer or an HBlank transfer of 16 bytes per HBlank. Writing
 * FF55 with bit 7 clear while HBlank DMA runs aborts it.
 */
data class HdmaState(
    val src: Int = 0,
    val dst: Int = 0,
    val active: Boolean = false, // HBlank-mode transfer in progress
    val remaining: Int = 0,      // 16-byte blocks left
) {
    fun ff55Read(): Int = when {
        active -> (remaining - 1) and 0x7F
        remaining > 0 -> 0x80 or ((remaining - 1) and 0x7F) // aborted
        else -> 0xFF
    }
}

fun hdmaSrcHi(h: HdmaState, value: Int): HdmaState = h.copy(src = (h.src and 0x00F0) or (value shl 8))
fun hdmaSrcLo(h: HdmaState, value: Int): HdmaState = h.copy(src = (h.src and 0xFF00) or (value and 0xF0))
fun hdmaDstHi(h: HdmaState, value: Int): HdmaState = h.copy(dst = (h.dst and 0x00F0) or ((value and 0x1F) shl 8))
fun hdmaDstLo(h: HdmaState, value: Int): HdmaState = h.copy(dst = (h.dst and 0x1F00) or (value and 0xF0))
