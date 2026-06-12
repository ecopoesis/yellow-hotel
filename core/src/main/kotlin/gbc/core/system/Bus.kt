package gbc.core.system

import gbc.core.api.Ports
import gbc.core.cart.cartRead
import gbc.core.cart.cartWrite
import gbc.core.cpu.Sm83Bus
import gbc.core.cpu.stepCpu
import gbc.core.dma.dmaRequest
import gbc.core.dma.hdmaDstHi
import gbc.core.dma.hdmaDstLo
import gbc.core.dma.hdmaSrcHi
import gbc.core.dma.hdmaSrcLo
import gbc.core.joypad.p1Read
import gbc.core.joypad.p1Write
import gbc.core.ppu.PpuMode
import gbc.core.ppu.StatUpdate
import gbc.core.ppu.bcpdRead
import gbc.core.ppu.bcpdWrite
import gbc.core.ppu.bcpsWrite
import gbc.core.ppu.lcdcWrite
import gbc.core.ppu.lycWrite
import gbc.core.ppu.ocpdRead
import gbc.core.ppu.ocpdWrite
import gbc.core.ppu.ocpsWrite
import gbc.core.ppu.ppuTick
import gbc.core.ppu.statRead
import gbc.core.ppu.statWrite
import gbc.core.serial.serialCtrlWrite
import gbc.core.serial.serialTick
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
    var cpu = stepCpu(s.cpu, bus)
    var state = bus.state
    // KEY1 speed switch: STOP with the switch armed toggles speed instead of stopping
    if (cpu.stopped && state.mode == HwMode.Cgb && state.key1Armed) {
        cpu = cpu.copy(stopped = false)
        state = state.copy(
            doubleSpeed = !state.doubleSpeed,
            key1Armed = false,
            timer = state.timer.copy(sysCounter = 0), // the switch resets DIV
        )
    }
    return state.copy(cpu = cpu)
}

/** Steps until the PPU completes a frame; samples host input at the frame boundary. */
fun stepFrame(s: SystemState, ports: Ports = Ports.NONE): SystemState {
    var state = withButtons(s, ports.input.buttons())
    while (!state.ppu.frameReady) state = stepInstruction(state, ports)
    return state.copy(ppu = state.ppu.copy(frameReady = false))
}

/**
 * Injects the host button state (a [gbc.core.api.Button] mask). New presses on
 * selected P1 lines request the joypad interrupt, and any press wakes STOP.
 */
fun withButtons(s: SystemState, buttons: Int): SystemState {
    if (buttons == s.joypad.buttons) return s
    val pressed = buttons and s.joypad.buttons.inv()
    val sel = s.joypad.select
    val irq = (pressed and 0xF != 0 && sel and 0x10 == 0) ||
        ((pressed shr 4) and 0xF != 0 && sel and 0x20 == 0)
    return s.copy(
        joypad = s.joypad.copy(buttons = buttons),
        intr = if (irq) s.intr.copy(iff = s.intr.iff or 0x10) else s.intr,
        cpu = if (pressed != 0 && s.cpu.stopped) s.cpu.copy(stopped = false) else s.cpu,
    )
}

/**
 * Reads the bus as the CPU would, without ticking time or causing side effects.
 * Total: every address yields a byte. Used by debuggers and test harnesses.
 */
fun peek(s: SystemState, addr: Int): Int = when {
    addr < 0x8000 -> cartRead(s.cart, addr)
    addr < 0xA000 -> s.vram[s.ppu.vbk * 0x2000 + (addr - 0x8000)].toInt() and 0xFF
    addr < 0xC000 -> cartRead(s.cart, addr)
    addr < 0xE000 -> s.wram[wramIndex(s, addr)].toInt() and 0xFF
    addr < 0xFE00 -> peek(s, addr - 0x2000) // echo RAM
    addr < 0xFEA0 -> s.oam[addr - 0xFE00].toInt() and 0xFF
    addr < 0xFF00 -> 0x00 // prohibited region
    addr < 0xFF80 -> ioPeek(s, addr and 0x7F)
    addr < 0xFFFF -> s.hram[addr - 0xFF80].toInt() and 0xFF
    else -> s.intr.ie
}

private fun wramIndex(s: SystemState, addr: Int): Int = when {
    addr < 0xD000 -> addr - 0xC000
    else -> maxOf(1, s.svbk and 7) * 0x1000 + (addr - 0xD000)
}

private fun ioPeek(s: SystemState, reg: Int): Int = when (reg) {
    0x00 -> p1Read(s.joypad)
    0x01 -> s.serial.data
    0x02 -> s.serial.ctrl or 0x7E
    0x04 -> timerDivRead(s.timer)
    0x05 -> s.timer.tima
    0x06 -> s.timer.tma
    0x07 -> timerTacRead(s.timer)
    0x0F -> 0xE0 or s.intr.iff
    0x40 -> s.ppu.lcdc
    0x41 -> statRead(s.ppu)
    0x42 -> s.ppu.scy
    0x43 -> s.ppu.scx
    0x44 -> s.ppu.ly
    0x45 -> s.ppu.lyc
    0x46 -> s.dma.reg
    0x47 -> s.ppu.bgp
    0x48 -> s.ppu.obp0
    0x49 -> s.ppu.obp1
    0x4A -> s.ppu.wy
    0x4B -> s.ppu.wx
    0x4D -> if (s.mode == HwMode.Cgb) {
        0x7E or (if (s.doubleSpeed) 0x80 else 0) or (if (s.key1Armed) 1 else 0)
    } else {
        0xFF
    }
    0x4F -> if (s.mode == HwMode.Cgb) 0xFE or s.ppu.vbk else 0xFF
    0x51, 0x52, 0x53, 0x54 -> 0xFF // HDMA setup registers are write-only
    0x55 -> if (s.mode == HwMode.Cgb) s.hdma.ff55Read() else 0xFF
    0x68 -> if (s.mode == HwMode.Cgb) 0x40 or s.ppu.bcps else 0xFF
    0x69 -> if (s.mode == HwMode.Cgb) bcpdRead(s.ppu) else 0xFF
    0x6A -> if (s.mode == HwMode.Cgb) 0x40 or s.ppu.ocps else 0xFF
    0x6B -> if (s.mode == HwMode.Cgb) ocpdRead(s.ppu) else 0xFF
    0x6C -> if (s.mode == HwMode.Cgb) 0xFE or s.ppu.opri else 0xFF
    0x70 -> if (s.mode == HwMode.Cgb) 0xF8 or s.svbk else 0xFF
    else -> if (ioExists(reg)) s.io[reg].toInt() and 0xFF else 0xFF
}

/**
 * Registers that exist as raw bytes on DMG hardware. Gaps read 0xFF (open
 * bus). Blargg's runtime depends on this: it probes KEY1 before attempting a
 * speed switch. CGB-only registers are handled explicitly above.
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

    private fun cgb() = state.mode == HwMode.Cgb

    /** One M-cycle of machine time. The APU (M7) also hooks in here. */
    private fun tick() {
        tickDma()
        val prevPpuMode = state.ppu.mode
        val timer = timerTick(state.timer)
        // In double speed the CPU clock doubles while the PPU stays real-time:
        // each CPU M-cycle is worth only 2 dots.
        val ppu = ppuTick(state.ppu, state.vram, state.oam, if (state.doubleSpeed) 2 else 4)
        val serial = serialTick(state.serial, 4)
        if (serial.emitted >= 0) ports.serial.byte(serial.emitted)
        var iff = state.intr.iff
        if (timer.irq) iff = iff or 0x04
        if (ppu.irqVblank) iff = iff or 0x01
        if (ppu.irqStat) iff = iff or 0x02
        if (serial.irq) iff = iff or 0x08
        state = state.copy(
            timer = timer.timer,
            ppu = ppu.ppu,
            serial = serial.serial,
            intr = if (iff != state.intr.iff) state.intr.copy(iff = iff) else state.intr,
            tCycles = state.tCycles + 4,
        )
        if (state.hdma.active && ppu.ppu.mode == PpuMode.HBlank &&
            prevPpuMode != PpuMode.HBlank && ppu.ppu.ly < 144
        ) {
            copyHdmaBlock()
            if (state.hdma.remaining == 0) state = state.copy(hdma = state.hdma.copy(active = false))
        }
    }

    private fun copyHdmaBlock() {
        val h = state.hdma
        val vramBase = state.ppu.vbk * 0x2000
        for (i in 0 until 16) {
            state.vram[vramBase + ((h.dst + i) and 0x1FFF)] = peek(state, (h.src + i) and 0xFFFF).toByte()
        }
        state = state.copy(
            hdma = h.copy(
                src = (h.src + 16) and 0xFFFF,
                dst = (h.dst + 16) and 0x1FFF,
                remaining = h.remaining - 1,
            ),
        )
    }

    private fun hdmaControl(value: Int) {
        val h = state.hdma
        if (h.active && value and 0x80 == 0) {
            state = state.copy(hdma = h.copy(active = false)) // abort, remaining preserved
            return
        }
        val blocks = (value and 0x7F) + 1
        if (value and 0x80 == 0) {
            // General-purpose DMA: copy everything now, charging machine time per block
            state = state.copy(hdma = h.copy(remaining = blocks, active = false))
            repeat(blocks) {
                copyHdmaBlock()
                repeat(8) { tick() }
            }
        } else {
            state = state.copy(hdma = h.copy(active = true, remaining = blocks))
        }
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
        state.dma.running && addr < 0xFF00 -> when {
            addr in 0xFE00..0xFE9F -> 0xFF
            state.dma.conflictsWith(addr) -> state.dma.busByte
            else -> lockedRead(addr)
        }
        else -> lockedRead(addr)
    }

    /** PPU-mode locking: VRAM is CPU-inaccessible in mode 3, OAM in modes 2 and 3. */
    private fun lockedRead(addr: Int): Int = when {
        addr in 0x8000..0x9FFF && vramLocked() -> 0xFF
        addr in 0xFE00..0xFE9F && oamLocked() -> 0xFF
        else -> peek(state, addr)
    }

    private fun vramLocked(): Boolean =
        state.ppu.lcdc and 0x80 != 0 && state.ppu.mode == PpuMode.Transfer

    private fun oamLocked(): Boolean =
        state.ppu.lcdc and 0x80 != 0 &&
            (state.ppu.mode == PpuMode.Transfer || state.ppu.mode == PpuMode.OamScan)

    private fun writeAt(addr: Int, value: Int) {
        when {
            addr < 0x8000 -> state = state.copy(cart = cartWrite(state.cart, addr, value))
            addr < 0xA000 -> if (!vramLocked()) {
                state.vram[state.ppu.vbk * 0x2000 + (addr - 0x8000)] = value.toByte()
            }
            addr < 0xC000 -> state = state.copy(cart = cartWrite(state.cart, addr, value))
            addr < 0xE000 -> state.wram[wramIndex(state, addr)] = value.toByte()
            addr < 0xFE00 -> writeAt(addr - 0x2000, value) // echo RAM
            addr < 0xFEA0 -> if (!state.dma.running && !oamLocked()) state.oam[addr - 0xFE00] = value.toByte()
            addr < 0xFF00 -> {} // prohibited region: ignored
            addr < 0xFF80 -> ioWrite(addr and 0x7F, value)
            addr < 0xFFFF -> state.hram[addr - 0xFF80] = value.toByte()
            else -> state = state.copy(intr = state.intr.copy(ie = value))
        }
    }

    private fun ioWrite(reg: Int, value: Int) {
        when (reg) {
            0x00 -> state = state.copy(joypad = p1Write(state.joypad, value))
            0x01 -> state = state.copy(serial = state.serial.copy(data = value))
            0x02 -> state = state.copy(serial = serialCtrlWrite(state.serial, value))
            0x04 -> state = state.copy(timer = timerDivWrite(state.timer).timer)
            0x05 -> state = state.copy(timer = timerTimaWrite(state.timer, value))
            0x06 -> state = state.copy(timer = timerTmaWrite(state.timer, value))
            0x07 -> state = state.copy(timer = timerTacWrite(state.timer, value).timer)
            0x0F -> state = state.copy(intr = state.intr.copy(iff = value and 0x1F))
            0x40 -> applyStatUpdate(lcdcWrite(state.ppu, value))
            0x41 -> applyStatUpdate(statWrite(state.ppu, value, dmgGlitch = state.mode == HwMode.Dmg))
            0x42 -> state = state.copy(ppu = state.ppu.copy(scy = value))
            0x43 -> state = state.copy(ppu = state.ppu.copy(scx = value))
            0x44 -> {} // LY is read-only
            0x45 -> state = state.copy(ppu = lycWrite(state.ppu, value))
            0x46 -> state = state.copy(dma = dmaRequest(state.dma, value))
            0x47 -> state = state.copy(ppu = state.ppu.copy(bgp = value))
            0x48 -> state = state.copy(ppu = state.ppu.copy(obp0 = value))
            0x49 -> state = state.copy(ppu = state.ppu.copy(obp1 = value))
            0x4A -> state = state.copy(ppu = state.ppu.copy(wy = value))
            0x4B -> state = state.copy(ppu = state.ppu.copy(wx = value))
            0x4D -> if (cgb()) state = state.copy(key1Armed = value and 1 != 0)
            0x4F -> if (cgb()) state = state.copy(ppu = state.ppu.copy(vbk = value and 1))
            0x51 -> if (cgb()) state = state.copy(hdma = hdmaSrcHi(state.hdma, value))
            0x52 -> if (cgb()) state = state.copy(hdma = hdmaSrcLo(state.hdma, value))
            0x53 -> if (cgb()) state = state.copy(hdma = hdmaDstHi(state.hdma, value))
            0x54 -> if (cgb()) state = state.copy(hdma = hdmaDstLo(state.hdma, value))
            0x55 -> if (cgb()) hdmaControl(value)
            0x68 -> if (cgb()) state = state.copy(ppu = bcpsWrite(state.ppu, value))
            0x69 -> if (cgb()) state = state.copy(ppu = bcpdWrite(state.ppu, value))
            0x6A -> if (cgb()) state = state.copy(ppu = ocpsWrite(state.ppu, value))
            0x6B -> if (cgb()) state = state.copy(ppu = ocpdWrite(state.ppu, value))
            0x6C -> if (cgb()) state = state.copy(ppu = state.ppu.copy(opri = value and 1))
            0x70 -> if (cgb()) state = state.copy(svbk = value and 7)
            else -> if (ioExists(reg)) state.io[reg] = value.toByte()
        }
    }

    private fun applyStatUpdate(update: StatUpdate) {
        state = state.copy(
            ppu = update.ppu,
            intr = if (update.irq) state.intr.copy(iff = state.intr.iff or 0x02) else state.intr,
        )
    }

}
