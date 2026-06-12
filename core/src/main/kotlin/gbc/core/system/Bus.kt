package gbc.core.system

import gbc.core.api.Ports
import gbc.core.cart.cartRead
import gbc.core.cart.cartWrite
import gbc.core.cpu.Sm83Bus
import gbc.core.cpu.stepCpu
import gbc.core.dma.dmaRequest
import gbc.core.timer.timerDivRead
import gbc.core.timer.timerDivWrite
import gbc.core.timer.timerTacRead
import gbc.core.timer.timerTacWrite
import gbc.core.timer.timerTick
import gbc.core.timer.timerTimaWrite
import gbc.core.timer.timerTmaWrite

/**
 * Executes one CPU step (instruction, dispatch, or idle halt cycle) against the
 * real bus. Tick-on-access: every M-cycle the CPU spends advances the rest of
 * the machine first, so peripherals see accesses at hardware-accurate times.
 */
fun stepInstruction(s: SystemState, ports: Ports = Ports.NONE): SystemState {
    val bus = SystemBus(s, ports)
    val cpu = stepCpu(s.cpu, bus)
    return bus.state.copy(cpu = cpu)
}

/**
 * Reads the bus as the CPU would, without ticking time or causing side effects.
 * Total: every address yields a byte. Used by debuggers and test harnesses.
 */
fun peek(s: SystemState, addr: Int): Int = when {
    addr < 0x8000 -> cartRead(s.cart, addr)
    addr < 0xA000 -> s.vram[addr - 0x8000].toInt() and 0xFF // VBK banking lands in M6
    addr < 0xC000 -> cartRead(s.cart, addr)
    addr < 0xE000 -> s.wram[addr - 0xC000].toInt() and 0xFF // SVBK banking lands in M6
    addr < 0xFE00 -> peek(s, addr - 0x2000) // echo RAM
    addr < 0xFEA0 -> s.oam[addr - 0xFE00].toInt() and 0xFF
    addr < 0xFF00 -> 0x00 // prohibited region
    addr < 0xFF80 -> ioPeek(s, addr and 0x7F)
    addr < 0xFFFF -> s.hram[addr - 0xFF80].toInt() and 0xFF
    else -> s.intr.ie
}

private fun ioPeek(s: SystemState, reg: Int): Int = when (reg) {
    0x01 -> s.serial.data
    0x02 -> s.serial.ctrl or 0x7E
    0x04 -> timerDivRead(s.timer)
    0x05 -> s.timer.tima
    0x06 -> s.timer.tma
    0x07 -> timerTacRead(s.timer)
    0x0F -> 0xE0 or s.intr.iff
    0x46 -> s.dma.reg
    else -> if (ioExists(reg)) s.io[reg].toInt() and 0xFF else 0xFF
}

/**
 * Registers that exist on DMG hardware. Everything else — gaps and CGB-only
 * registers like KEY1/VBK/SVBK/HDMA — reads 0xFF (open bus). Blargg's runtime
 * depends on this: it probes KEY1 before attempting a speed switch. CGB-mode
 * registers join in M6.
 */
private fun ioExists(reg: Int): Boolean = when (reg) {
    0x00 -> true // P1
    in 0x10..0x14, in 0x16..0x1E, in 0x20..0x26, in 0x30..0x3F -> true // APU + wave RAM
    in 0x40..0x4B -> true // PPU registers
    else -> false
}

private class SystemBus(var state: SystemState, private val ports: Ports) : Sm83Bus {

    override fun read(addr: Int): Int {
        tick()
        return readAt(addr)
    }

    override fun write(addr: Int, value: Int) {
        tick()
        writeAt(addr, value and 0xFF)
    }

    override fun internalCycle() = tick()

    override fun pendingInterrupts(): Int = state.intr.ie and state.intr.iff and 0x1F

    override fun ackInterrupt(bit: Int) {
        state = state.copy(intr = state.intr.copy(iff = state.intr.iff and bit.inv()))
    }

    /** One M-cycle of machine time. PPU (M4) and APU (M7) also hook in here. */
    private fun tick() {
        tickDma()
        val timer = timerTick(state.timer)
        state = state.copy(
            timer = timer.timer,
            intr = if (timer.irq) state.intr.copy(iff = state.intr.iff or 0x04) else state.intr,
            tCycles = state.tCycles + 4,
        )
    }

    private fun tickDma() {
        var d = state.dma
        if (d.running) {
            val value = peek(state, d.source + d.index)
            state.oam[d.index] = value.toByte()
            d = d.copy(index = d.index + 1, busByte = value, running = d.index + 1 < 160)
        }
        if (d.pendingDelay > 0) {
            d = d.copy(pendingDelay = d.pendingDelay - 1)
            if (d.pendingDelay == 0) d = d.copy(running = true, source = d.pendingSource, index = 0)
        }
        if (d !== state.dma) state = state.copy(dma = d)
    }

    /**
     * What the CPU sees: while OAM DMA runs it can truly reach only HRAM and
     * IO. OAM reads 0xFF, and reads on the bus the DMA occupies return the
     * byte being transferred. (Instruction fetches included — that is why
     * games run their DMA wait loop from HRAM.)
     */
    private fun readAt(addr: Int): Int = when {
        !state.dma.running || addr >= 0xFF00 -> peek(state, addr)
        addr in 0xFE00..0xFE9F -> 0xFF
        state.dma.conflictsWith(addr) -> state.dma.busByte
        else -> peek(state, addr)
    }

    private fun writeAt(addr: Int, value: Int) {
        when {
            addr < 0x8000 -> state = state.copy(cart = cartWrite(state.cart, addr, value))
            addr < 0xA000 -> state.vram[addr - 0x8000] = value.toByte()
            addr < 0xC000 -> state = state.copy(cart = cartWrite(state.cart, addr, value))
            addr < 0xE000 -> state.wram[addr - 0xC000] = value.toByte()
            addr < 0xFE00 -> writeAt(addr - 0x2000, value) // echo RAM
            addr < 0xFEA0 -> if (!state.dma.running) state.oam[addr - 0xFE00] = value.toByte()
            addr < 0xFF00 -> {} // prohibited region: ignored
            addr < 0xFF80 -> ioWrite(addr and 0x7F, value)
            addr < 0xFFFF -> state.hram[addr - 0xFF80] = value.toByte()
            else -> state = state.copy(intr = state.intr.copy(ie = value))
        }
    }

    private fun ioWrite(reg: Int, value: Int) {
        when (reg) {
            0x01 -> state = state.copy(serial = state.serial.copy(data = value))
            0x02 -> serialControl(value)
            0x04 -> state = state.copy(timer = timerDivWrite(state.timer).timer)
            0x05 -> state = state.copy(timer = timerTimaWrite(state.timer, value))
            0x06 -> state = state.copy(timer = timerTmaWrite(state.timer, value))
            0x07 -> state = state.copy(timer = timerTacWrite(state.timer, value).timer)
            0x0F -> state = state.copy(intr = state.intr.copy(iff = value and 0x1F))
            0x46 -> state = state.copy(dma = dmaRequest(state.dma, value))
            else -> if (ioExists(reg)) state.io[reg] = value.toByte()
        }
    }

    /**
     * Until M5 brings real bit-clocking, an internally-clocked transfer
     * completes instantly: the byte goes to the host port, 0xFF shifts in
     * (nothing on the other end), and the serial interrupt is requested.
     */
    private fun serialControl(value: Int) {
        if (value and 0x81 == 0x81) {
            ports.serial.byte(state.serial.data)
            state = state.copy(
                serial = SerialState(data = 0xFF, ctrl = value and 0x7F),
                intr = state.intr.copy(iff = state.intr.iff or 0x08),
            )
        } else {
            state = state.copy(serial = state.serial.copy(ctrl = value))
        }
    }
}
