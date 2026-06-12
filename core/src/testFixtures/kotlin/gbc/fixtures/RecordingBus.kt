package gbc.fixtures

import gbc.core.cpu.Sm83Bus

/**
 * Flat 64 KiB RAM bus that records per-M-cycle bus activity in the
 * SingleStepTests convention: ("r-m" reads, "-wm" writes, "---" idle cycles
 * repeating the previous access's address/data).
 */
class RecordingBus(
    val mem: ByteArray = ByteArray(0x10000),
    private var interruptFlags: Int = 0,
    var interruptEnable: Int = 0,
) : Sm83Bus {

    data class Cycle(val addr: Int, val data: Int, val kind: String)

    val cycles = mutableListOf<Cycle>()
    private var lastAddr = 0
    private var lastData = 0

    override fun read(addr: Int): Int {
        val value = mem[addr].toInt() and 0xFF
        lastAddr = addr
        lastData = value
        cycles += Cycle(addr, value, "r-m")
        return value
    }

    override fun write(addr: Int, value: Int) {
        mem[addr] = value.toByte()
        if (addr == 0xFFFF) interruptEnable = value and 0xFF
        lastAddr = addr
        lastData = value and 0xFF
        cycles += Cycle(addr, value and 0xFF, "-wm")
    }

    override fun internalCycle() {
        cycles += Cycle(lastAddr, lastData, "---")
    }

    override fun pendingInterrupts(): Int = interruptEnable and interruptFlags and 0x1F

    override fun ackInterrupt(bit: Int) {
        interruptFlags = interruptFlags and bit.inv()
    }

    fun raiseInterrupt(bit: Int) {
        interruptFlags = interruptFlags or bit
    }
}
