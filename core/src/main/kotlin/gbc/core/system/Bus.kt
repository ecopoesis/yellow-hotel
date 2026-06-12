package gbc.core.system

import gbc.core.api.Ports
import gbc.core.apu.ApuRun
import gbc.core.apu.ApuState
import gbc.core.apu.apuRead
import gbc.core.apu.apuWrite
import gbc.core.apu.waveRamRead
import gbc.core.apu.waveRamWrite
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
import gbc.core.ppu.PpuRun
import gbc.core.ppu.PpuState
import gbc.core.ppu.StatUpdate
import gbc.core.ppu.bcpdRead
import gbc.core.ppu.bcpdWrite
import gbc.core.ppu.bcpsWrite
import gbc.core.ppu.lcdcWrite
import gbc.core.ppu.lycWrite
import gbc.core.ppu.ocpdRead
import gbc.core.ppu.ocpdWrite
import gbc.core.ppu.ocpsWrite
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
    var state = bus.snapshot()
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

/**
 * Steps until the PPU completes a frame; samples host input at the frame
 * boundary. One bus instance lives for the whole frame so state materializes
 * once per frame instead of once per instruction — semantics are identical.
 */
fun stepFrame(s: SystemState, ports: Ports = Ports.NONE): SystemState {
    var state = withButtons(s, ports.input.buttons())
    while (true) {
        val bus = SystemBus(state, ports)
        var cpu = state.cpu
        while (!bus.frameReadyNow() && !(cpu.stopped && bus.speedSwitchArmed())) {
            cpu = stepCpu(cpu, bus)
        }
        state = bus.snapshot().copy(cpu = cpu)
        if (state.ppu.frameReady) {
            return state.copy(ppu = state.ppu.copy(frameReady = false))
        }
        // KEY1 speed switch: apply and continue the frame with a fresh bus
        state = state.copy(
            cpu = cpu.copy(stopped = false),
            doubleSpeed = !state.doubleSpeed,
            key1Armed = false,
            timer = state.timer.copy(sysCounter = 0),
        )
    }
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
    addr < 0xE000 -> s.wram[wramIndex(s.svbk, addr)].toInt() and 0xFF
    addr < 0xFE00 -> peek(s, addr - 0x2000) // echo RAM
    addr < 0xFEA0 -> s.oam[addr - 0xFE00].toInt() and 0xFF
    addr < 0xFF00 -> 0x00 // prohibited region
    addr < 0xFF80 -> ioPeek(s, addr and 0x7F)
    addr < 0xFFFF -> s.hram[addr - 0xFF80].toInt() and 0xFF
    else -> s.intr.ie
}

private fun wramIndex(svbk: Int, addr: Int): Int = when {
    addr < 0xD000 -> addr - 0xC000
    else -> maxOf(1, svbk and 7) * 0x1000 + (addr - 0xD000)
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
    in 0x10..0x26 -> apuRead(s.apu, reg)
    in 0x30..0x3F -> waveRamRead(s.apu, reg - 0x30)
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
    else -> 0xFF // unmapped register: open bus
}

/**
 * Hot-path bus. Sub-states are hoisted into fields and the PPU/APU advance
 * through reusable run objects, so the full SystemState (and the large
 * PpuState/ApuState graphs) materialize once per instruction instead of once
 * per M-cycle. Per-M-cycle semantics are unchanged.
 */
private class SystemBus(s: SystemState, private val ports: Ports) : Sm83Bus {

    private val mode = s.mode
    private val vram = s.vram
    private val wram = s.wram
    private val hram = s.hram
    private val oam = s.oam
    private var cart = s.cart
    private var iff = s.intr.iff
    private var ie = s.intr.ie
    private var serial = s.serial
    private var timer = s.timer
    private var dma = s.dma
    private var hdma = s.hdma
    private var joypad = s.joypad
    private var svbk = s.svbk
    private val doubleSpeed = s.doubleSpeed
    private var key1Armed = s.key1Armed
    private var tCycles = s.tCycles
    private var ppu = s.ppu // authoritative when ppuRun == null
    private var ppuRun: PpuRun? = null
    private var apu = s.apu // authoritative when apuRun == null
    private var apuRun: ApuRun? = null
    private val original = s

    fun snapshot(): SystemState {
        flushPpu()
        flushApu()
        return original.copy(
            cart = cart,
            intr = InterruptState(iff = iff, ie = ie),
            serial = serial,
            timer = timer,
            dma = dma,
            ppu = ppu,
            joypad = joypad,
            hdma = hdma,
            apu = apu,
            svbk = svbk,
            key1Armed = key1Armed,
            tCycles = tCycles,
        )
    }

    private fun flushPpu() {
        ppuRun?.let {
            ppu = it.toState()
            ppuRun = null
        }
    }

    private fun flushApu() {
        apuRun?.let {
            apu = it.toState()
            apuRun = null
        }
    }

    private fun ppuMode(): PpuMode = ppuRun?.modeNow ?: ppu.mode

    private fun ppuLy(): Int = ppuRun?.lyNow ?: ppu.ly

    fun frameReadyNow(): Boolean = ppuRun?.frameReadyNow ?: ppu.frameReady

    fun speedSwitchArmed(): Boolean = mode == HwMode.Cgb && key1Armed

    override fun read(addr: Int): Int {
        tick()
        return readAt(addr)
    }

    override fun write(addr: Int, value: Int) {
        tick()
        writeAt(addr, value and 0xFF)
    }

    override fun internalCycle() = tick()

    override fun pendingInterrupts(): Int = ie and iff and 0x1F

    override fun ackInterrupt(bit: Int) {
        iff = iff and bit.inv()
    }

    private fun cgb() = mode == HwMode.Cgb

    /** One M-cycle of machine time. */
    private fun tick() {
        tickDma()
        val t = timerTick(timer)
        timer = t.timer
        if (t.irq) iff = iff or 0x04
        // In double speed the CPU clock doubles while the PPU/APU stay
        // real-time: each CPU M-cycle is worth only 2 dots / 2 T-cycles.
        val dots = if (doubleSpeed) 2 else 4
        if (ppu.lcdc and 0x80 != 0) {
            val run = ppuRun ?: PpuRun(ppu, vram, oam).also { ppuRun = it }
            val prevMode = run.modeNow
            repeat(dots) { run.dot() }
            iff = iff or run.consumeIrqs()
            if (hdma.active && run.modeNow == PpuMode.HBlank &&
                prevMode != PpuMode.HBlank && run.lyNow < 144
            ) {
                copyHdmaBlock()
                if (hdma.remaining == 0) hdma = hdma.copy(active = false)
            }
        }
        val divBit = (timer.sysCounter shr (if (doubleSpeed) 13 else 12)) and 1
        val run = apuRun ?: ApuRun(apu).also { apuRun = it }
        run.tick(divBit == 1, dots)
        val st = serialTick(serial, 4)
        serial = st.serial
        if (st.emitted >= 0) ports.serial.byte(st.emitted)
        if (st.irq) iff = iff or 0x08
        tCycles += 4
    }

    private fun copyHdmaBlock() {
        val vramBase = ppu.vbk * 0x2000
        for (i in 0 until 16) {
            vram[vramBase + ((hdma.dst + i) and 0x1FFF)] = liveRead((hdma.src + i) and 0xFFFF).toByte()
        }
        hdma = hdma.copy(
            src = (hdma.src + 16) and 0xFFFF,
            dst = (hdma.dst + 16) and 0x1FFF,
            remaining = hdma.remaining - 1,
        )
    }

    private fun hdmaControl(value: Int) {
        if (hdma.active && value and 0x80 == 0) {
            hdma = hdma.copy(active = false) // abort, remaining preserved
            return
        }
        val blocks = (value and 0x7F) + 1
        if (value and 0x80 == 0) {
            // General-purpose DMA: copy everything now, charging machine time per block
            hdma = hdma.copy(remaining = blocks, active = false)
            repeat(blocks) {
                copyHdmaBlock()
                repeat(8) { tick() }
            }
        } else {
            hdma = hdma.copy(active = true, remaining = blocks)
        }
    }

    private fun tickDma() {
        var d = dma
        if (d.running) {
            val value = liveRead(d.source + d.index)
            oam[d.index] = value.toByte()
            d = d.copy(index = d.index + 1, busByte = value, running = d.index + 1 < 160)
        }
        if (d.pendingDelay > 0) {
            d = d.copy(pendingDelay = d.pendingDelay - 1)
            if (d.pendingDelay == 0) d = d.copy(running = true, source = d.pendingSource, index = 0)
        }
        dma = d
    }

    /** Side-effect-free read against the live fields (no DMA/lock filtering). */
    private fun liveRead(addr: Int): Int = when {
        addr < 0x8000 -> cartRead(cart, addr)
        addr < 0xA000 -> vram[ppu.vbk * 0x2000 + (addr - 0x8000)].toInt() and 0xFF
        addr < 0xC000 -> cartRead(cart, addr)
        addr < 0xE000 -> wram[wramIndex(svbk, addr)].toInt() and 0xFF
        addr < 0xFE00 -> liveRead(addr - 0x2000) // echo RAM
        addr < 0xFEA0 -> oam[addr - 0xFE00].toInt() and 0xFF
        addr < 0xFF00 -> 0x00 // prohibited region
        addr < 0xFF80 -> ioRead(addr and 0x7F)
        addr < 0xFFFF -> hram[addr - 0xFF80].toInt() and 0xFF
        else -> ie
    }

    /**
     * What the CPU sees: while OAM DMA runs it can truly reach only HRAM and
     * IO. OAM reads 0xFF, and reads on the bus the DMA occupies return the
     * byte being transferred. (Instruction fetches included — that is why
     * games run their DMA wait loop from HRAM.)
     */
    private fun readAt(addr: Int): Int = when {
        dma.running && addr < 0xFF00 -> when {
            addr in 0xFE00..0xFE9F -> 0xFF
            dma.conflictsWith(addr) -> dma.busByte
            else -> lockedRead(addr)
        }
        else -> lockedRead(addr)
    }

    /** PPU-mode locking: VRAM is CPU-inaccessible in mode 3, OAM in modes 2 and 3. */
    private fun lockedRead(addr: Int): Int = when {
        addr in 0x8000..0x9FFF && vramLocked() -> 0xFF
        addr in 0xFE00..0xFE9F && oamLocked() -> 0xFF
        else -> liveRead(addr)
    }

    private fun vramLocked(): Boolean =
        ppu.lcdc and 0x80 != 0 && ppuMode() == PpuMode.Transfer

    private fun oamLocked(): Boolean {
        if (ppu.lcdc and 0x80 == 0) return false
        val m = ppuMode()
        return m == PpuMode.Transfer || m == PpuMode.OamScan
    }

    private fun ioRead(reg: Int): Int = when (reg) {
        0x00 -> p1Read(joypad)
        0x01 -> serial.data
        0x02 -> serial.ctrl or 0x7E
        0x04 -> timerDivRead(timer)
        0x05 -> timer.tima
        0x06 -> timer.tma
        0x07 -> timerTacRead(timer)
        0x0F -> 0xE0 or iff
        in 0x10..0x26 -> {
            flushApu()
            apuRead(apu, reg)
        }
        in 0x30..0x3F -> {
            flushApu()
            waveRamRead(apu, reg - 0x30)
        }
        // STAT and LY are polled in tight loops: serve them from the live run
        0x41 -> 0x80 or ppu.statEnables or
            (if (ppuRun?.lycFlagNow ?: ppu.lycFlag) 0x04 else 0) or ppuMode().ordinal
        0x44 -> ppuLy()
        in 0x40..0x4B -> { // registers stable within a run except via writes (which flush)
            when (reg) {
                0x40 -> ppu.lcdc
                0x42 -> ppu.scy
                0x43 -> ppu.scx
                0x45 -> ppu.lyc
                0x46 -> dma.reg
                0x47 -> ppu.bgp
                0x48 -> ppu.obp0
                0x49 -> ppu.obp1
                0x4A -> ppu.wy
                else -> ppu.wx
            }
        }
        0x4D -> if (cgb()) {
            0x7E or (if (doubleSpeed) 0x80 else 0) or (if (key1Armed) 1 else 0)
        } else {
            0xFF
        }
        0x4F -> if (cgb()) 0xFE or ppu.vbk else 0xFF
        0x51, 0x52, 0x53, 0x54 -> 0xFF
        0x55 -> if (cgb()) hdma.ff55Read() else 0xFF
        0x68 -> if (cgb()) 0x40 or ppu.bcps else 0xFF
        0x69 -> if (cgb()) bcpdRead(ppu) else 0xFF
        0x6A -> if (cgb()) 0x40 or ppu.ocps else 0xFF
        0x6B -> if (cgb()) ocpdRead(ppu) else 0xFF
        0x6C -> if (cgb()) 0xFE or ppu.opri else 0xFF
        0x70 -> if (cgb()) 0xF8 or svbk else 0xFF
        else -> 0xFF // unmapped register: open bus (Blargg probes KEY1 this way)
    }

    private fun writeAt(addr: Int, value: Int) {
        when {
            addr < 0x8000 -> cart = cartWrite(cart, addr, value)
            addr < 0xA000 -> if (!vramLocked()) {
                vram[ppu.vbk * 0x2000 + (addr - 0x8000)] = value.toByte()
            }
            addr < 0xC000 -> cart = cartWrite(cart, addr, value)
            addr < 0xE000 -> wram[wramIndex(svbk, addr)] = value.toByte()
            addr < 0xFE00 -> writeAt(addr - 0x2000, value) // echo RAM
            addr < 0xFEA0 -> if (!dma.running && !oamLocked()) oam[addr - 0xFE00] = value.toByte()
            addr < 0xFF00 -> {} // prohibited region: ignored
            addr < 0xFF80 -> ioWrite(addr and 0x7F, value)
            addr < 0xFFFF -> hram[addr - 0xFF80] = value.toByte()
            else -> ie = value
        }
    }

    /** PPU register writes operate on materialized state; flush first. */
    private fun updatePpu(transform: (PpuState) -> PpuState) {
        flushPpu()
        ppu = transform(ppu)
    }

    private fun updateApu(transform: (ApuState) -> ApuState) {
        flushApu()
        apu = transform(apu)
    }

    private fun ioWrite(reg: Int, value: Int) {
        when (reg) {
            0x00 -> joypad = p1Write(joypad, value)
            0x01 -> serial = serial.copy(data = value)
            0x02 -> serial = serialCtrlWrite(serial, value)
            0x04 -> timer = timerDivWrite(timer).timer
            0x05 -> timer = timerTimaWrite(timer, value)
            0x06 -> timer = timerTmaWrite(timer, value)
            0x07 -> timer = timerTacWrite(timer, value).timer
            0x0F -> iff = value and 0x1F
            in 0x10..0x26 -> updateApu { apuWrite(it, reg, value) }
            in 0x30..0x3F -> updateApu { waveRamWrite(it, reg - 0x30, value) }
            0x40 -> applyStatUpdate { lcdcWrite(it, value) }
            0x41 -> applyStatUpdate { statWrite(it, value, dmgGlitch = mode == HwMode.Dmg) }
            0x42 -> updatePpu { it.copy(scy = value) }
            0x43 -> updatePpu { it.copy(scx = value) }
            0x44 -> {} // LY is read-only
            0x45 -> updatePpu { lycWrite(it, value) }
            0x46 -> dma = dmaRequest(dma, value)
            0x47 -> updatePpu { it.copy(bgp = value) }
            0x48 -> updatePpu { it.copy(obp0 = value) }
            0x49 -> updatePpu { it.copy(obp1 = value) }
            0x4A -> updatePpu { it.copy(wy = value) }
            0x4B -> updatePpu { it.copy(wx = value) }
            0x4D -> if (cgb()) key1Armed = value and 1 != 0
            0x4F -> if (cgb()) updatePpu { it.copy(vbk = value and 1) }
            0x51 -> if (cgb()) hdma = hdmaSrcHi(hdma, value)
            0x52 -> if (cgb()) hdma = hdmaSrcLo(hdma, value)
            0x53 -> if (cgb()) hdma = hdmaDstHi(hdma, value)
            0x54 -> if (cgb()) hdma = hdmaDstLo(hdma, value)
            0x55 -> if (cgb()) hdmaControl(value)
            0x68 -> if (cgb()) updatePpu { bcpsWrite(it, value) }
            0x69 -> if (cgb()) updatePpu { bcpdWrite(it, value) }
            0x6A -> if (cgb()) updatePpu { ocpsWrite(it, value) }
            0x6B -> if (cgb()) updatePpu { ocpdWrite(it, value) }
            0x6C -> if (cgb()) updatePpu { it.copy(opri = value and 1) }
            0x70 -> if (cgb()) svbk = value and 7
            else -> {} // unmapped register: write ignored
        }
    }

    private fun applyStatUpdate(produce: (PpuState) -> StatUpdate) {
        flushPpu()
        val update = produce(ppu)
        ppu = update.ppu
        if (update.irq) iff = iff or 0x02
    }
}
