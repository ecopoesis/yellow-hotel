package gbc.core.dma

/**
 * OAM DMA: 160 bytes from page<<8 into OAM, one byte per M-cycle, after a short
 * setup delay. While a transfer runs the CPU effectively owns only HRAM and IO:
 * reads from the bus the DMA source occupies return the byte currently being
 * transferred, and OAM itself reads 0xFF.
 */
data class OamDmaState(
    val reg: Int = 0xFF,          // FF46 readback
    val running: Boolean = false, // bytes are moving this very moment
    val source: Int = 0,
    val index: Int = 0,           // next byte to copy, 0..159
    val pendingSource: Int = 0,   // a write schedules a (re)start...
    val pendingDelay: Int = 0,    // ...this many M-cycles from now
    val busByte: Int = 0xFF,      // what a conflicting read sees
) {
    /** A transfer is in progress or scheduled. */
    val active: Boolean get() = running || pendingDelay > 0

    /** True when [addr] is on the external bus (cart ROM/RAM, WRAM, echo). */
    fun conflictsWith(addr: Int): Boolean = running && sameBus(addr, source + index)

    private fun sameBus(a: Int, b: Int): Boolean {
        val aVram = a in 0x8000..0x9FFF
        val bVram = b in 0x8000..0x9FFF
        return aVram == bVram
    }
}

/** FF46 write: schedule a transfer; an in-flight one keeps running during the delay. */
fun dmaRequest(d: OamDmaState, value: Int): OamDmaState {
    val page = value and 0xFF
    val base = if (page >= 0xE0) (page - 0x20) shl 8 else page shl 8 // echo mirror
    return d.copy(reg = page, pendingSource = base, pendingDelay = 1)
}
