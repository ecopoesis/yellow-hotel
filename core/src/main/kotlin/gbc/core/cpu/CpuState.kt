package gbc.core.cpu

/**
 * SM83 register/control state. All register math uses Int; values are kept
 * masked to their width (8-bit registers 0..0xFF, F low nibble always 0,
 * SP/PC 0..0xFFFF).
 */
data class CpuState(
    val a: Int = 0,
    val f: Int = 0,
    val b: Int = 0,
    val c: Int = 0,
    val d: Int = 0,
    val e: Int = 0,
    val h: Int = 0,
    val l: Int = 0,
    val sp: Int = 0,
    val pc: Int = 0,
    val ime: Boolean = false,
    /** EI took effect this instruction; IME turns on after the next one. */
    val eiPending: Boolean = false,
    val halted: Boolean = false,
    val stopped: Boolean = false,
    /** A hardware-invalid opcode was executed; the CPU is wedged until reset. */
    val locked: Boolean = false,
    /** HALT executed with IME=0 while an interrupt was pending: next fetch skips the PC increment. */
    val haltBug: Boolean = false,
)

/**
 * The CPU's view of the machine. Every read/write/internalCycle costs exactly
 * one M-cycle (4 T-cycles); implementations advance the rest of the system
 * before satisfying the access (tick-on-access).
 */
interface Sm83Bus {
    fun read(addr: Int): Int
    fun write(addr: Int, value: Int)
    fun internalCycle()

    /** IE & IF & 0x1F as currently visible to the CPU. */
    fun pendingInterrupts(): Int

    /** Clear one IF bit during dispatch. */
    fun ackInterrupt(bit: Int)
}
