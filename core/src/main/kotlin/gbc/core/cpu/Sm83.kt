package gbc.core.cpu

private const val Z = 0x80
private const val N = 0x40
private const val H = 0x20
private const val C = 0x10

/**
 * Executes one CPU step: a single instruction, an interrupt dispatch, or one
 * idle M-cycle when halted/stopped/locked. Pure at the boundary: the returned
 * CpuState is the only register mutation; bus side effects happen through [bus]
 * at the M-cycle they occur on hardware.
 */
fun stepCpu(cpu: CpuState, bus: Sm83Bus): CpuState {
    if (cpu.locked) {
        bus.internalCycle()
        return cpu
    }
    if (cpu.halted || cpu.stopped) {
        bus.internalCycle()
        if (cpu.halted && bus.pendingInterrupts() != 0) {
            val woken = cpu.copy(halted = false)
            return if (cpu.ime) dispatch(woken, bus) else woken
        }
        return cpu // STOP wake (joypad) is handled by the system layer
    }
    if (cpu.ime && bus.pendingInterrupts() != 0) return dispatch(cpu, bus)
    val run = Sm83Run(cpu, bus)
    run.execute()
    return run.toState()
}

/**
 * 5 M-cycles: two internal, push PCH, push PCL, one internal while PC loads the
 * vector. Pending interrupts are re-evaluated after the PCH push, so a push that
 * lands on IE can cancel the dispatch — the CPU then jumps to 0x0000 (hardware
 * quirk, Mooneye ie_push).
 */
private fun dispatch(cpu: CpuState, bus: Sm83Bus): CpuState {
    bus.internalCycle()
    bus.internalCycle()
    var sp = (cpu.sp - 1) and 0xFFFF
    bus.write(sp, (cpu.pc shr 8) and 0xFF)
    val pending = bus.pendingInterrupts()
    sp = (sp - 1) and 0xFFFF
    bus.write(sp, cpu.pc and 0xFF)
    bus.internalCycle()
    val bit = pending and (-pending) // lowest set bit wins: VBlank > STAT > Timer > Serial > Joypad
    val vector = if (bit == 0) {
        0x0000
    } else {
        bus.ackInterrupt(bit)
        0x40 + 8 * Integer.numberOfTrailingZeros(bit)
    }
    return cpu.copy(pc = vector, sp = sp, ime = false, eiPending = false)
}

@Suppress("CyclomaticComplexMethod") // the opcode matrix is irreducibly a 256-way dispatch
private class Sm83Run(s: CpuState, private val bus: Sm83Bus) {
    private var a = s.a
    private var f = s.f
    private var b = s.b
    private var c = s.c
    private var d = s.d
    private var e = s.e
    private var h = s.h
    private var l = s.l
    private var sp = s.sp
    private var pc = s.pc
    private var ime = s.ime
    private var eiPending = s.eiPending
    private var halted = false
    private var stopped = s.stopped
    private var locked = false
    private var haltBug = s.haltBug
    private var suppressEnable = false

    fun toState() = CpuState(a, f, b, c, d, e, h, l, sp, pc, ime, eiPending, halted, stopped, locked, haltBug)

    private var hl: Int
        get() = (h shl 8) or l
        set(v) {
            h = (v shr 8) and 0xFF
            l = v and 0xFF
        }
    private var bc: Int
        get() = (b shl 8) or c
        set(v) {
            b = (v shr 8) and 0xFF
            c = v and 0xFF
        }
    private var de: Int
        get() = (d shl 8) or e
        set(v) {
            d = (v shr 8) and 0xFF
            e = v and 0xFF
        }

    private val fz get() = f and Z != 0
    private val fn get() = f and N != 0
    private val fh get() = f and H != 0
    private val fc get() = f and C != 0

    private fun flags(z: Boolean, n: Boolean, h: Boolean, c: Boolean) {
        f = (if (z) Z else 0) or (if (n) N else 0) or (if (h) H else 0) or (if (c) C else 0)
    }

    private fun imm8(): Int {
        val v = bus.read(pc)
        pc = (pc + 1) and 0xFFFF
        return v
    }

    private fun imm16(): Int {
        val lo = imm8()
        return lo or (imm8() shl 8)
    }

    private fun get8(i: Int): Int = when (i) {
        0 -> b
        1 -> c
        2 -> d
        3 -> e
        4 -> h
        5 -> l
        6 -> bus.read(hl)
        else -> a
    }

    private fun set8(i: Int, v: Int) = when (i) {
        0 -> b = v
        1 -> c = v
        2 -> d = v
        3 -> e = v
        4 -> h = v
        5 -> l = v
        6 -> bus.write(hl, v)
        else -> a = v
    }

    fun execute() {
        val enableImeAfter = eiPending
        val op = bus.read(pc)
        if (haltBug) haltBug = false else pc = (pc + 1) and 0xFFFF
        when {
            op in 0x40..0x7F && op != 0x76 -> set8((op shr 3) and 7, get8(op and 7))
            op in 0x80..0xBF -> alu((op shr 3) and 7, get8(op and 7))
            else -> other(op)
        }
        if (enableImeAfter && !suppressEnable) {
            ime = true
            eiPending = false
        }
    }

    private fun alu(kind: Int, v: Int) = when (kind) {
        0 -> add(v, 0)
        1 -> add(v, if (fc) 1 else 0)
        2 -> sub(v, 0, store = true)
        3 -> sub(v, if (fc) 1 else 0, store = true)
        4 -> {
            a = a and v
            flags(a == 0, n = false, h = true, c = false)
        }
        5 -> {
            a = a xor v
            flags(a == 0, n = false, h = false, c = false)
        }
        6 -> {
            a = a or v
            flags(a == 0, n = false, h = false, c = false)
        }
        else -> sub(v, 0, store = false)
    }

    private fun add(v: Int, carry: Int) {
        val r = a + v + carry
        flags((r and 0xFF) == 0, n = false, h = (a and 0xF) + (v and 0xF) + carry > 0xF, c = r > 0xFF)
        a = r and 0xFF
    }

    private fun sub(v: Int, carry: Int, store: Boolean) {
        val r = a - v - carry
        flags((r and 0xFF) == 0, n = true, h = (a and 0xF) - (v and 0xF) - carry < 0, c = r < 0)
        if (store) a = r and 0xFF
    }

    private fun inc8(v: Int): Int {
        val r = (v + 1) and 0xFF
        flags(r == 0, n = false, h = (v and 0xF) == 0xF, c = fc)
        return r
    }

    private fun dec8(v: Int): Int {
        val r = (v - 1) and 0xFF
        flags(r == 0, n = true, h = (v and 0xF) == 0, c = fc)
        return r
    }

    private fun addHl(v: Int) {
        bus.internalCycle()
        val r = hl + v
        flags(fz, n = false, h = (hl and 0xFFF) + (v and 0xFFF) > 0xFFF, c = r > 0xFFFF)
        hl = r and 0xFFFF
    }

    private fun spPlusImm(): Int {
        val raw = imm8()
        val off = raw.toByte().toInt()
        flags(z = false, n = false, h = (sp and 0xF) + (raw and 0xF) > 0xF, c = (sp and 0xFF) + raw > 0xFF)
        return (sp + off) and 0xFFFF
    }

    private fun push(v: Int) {
        bus.internalCycle()
        sp = (sp - 1) and 0xFFFF
        bus.write(sp, (v shr 8) and 0xFF)
        sp = (sp - 1) and 0xFFFF
        bus.write(sp, v and 0xFF)
    }

    private fun pop(): Int {
        val lo = bus.read(sp)
        sp = (sp + 1) and 0xFFFF
        val hi = bus.read(sp)
        sp = (sp + 1) and 0xFFFF
        return (hi shl 8) or lo
    }

    private fun jr(cond: Boolean) {
        val off = imm8().toByte().toInt()
        if (cond) {
            bus.internalCycle()
            pc = (pc + off) and 0xFFFF
        }
    }

    private fun jp(cond: Boolean) {
        val target = imm16()
        if (cond) {
            bus.internalCycle()
            pc = target
        }
    }

    private fun call(cond: Boolean) {
        val target = imm16()
        if (cond) {
            push(pc)
            pc = target
        }
    }

    private fun retCc(cond: Boolean) {
        bus.internalCycle()
        if (cond) {
            pc = pop()
            bus.internalCycle()
        }
    }

    private fun cond(i: Int): Boolean = when (i) {
        0 -> !fz
        1 -> fz
        2 -> !fc
        else -> fc
    }

    private fun other(op: Int) {
        when (op) {
            0x00 -> {}
            0x01 -> bc = imm16()
            0x11 -> de = imm16()
            0x21 -> hl = imm16()
            0x31 -> sp = imm16()
            0x02 -> bus.write(bc, a)
            0x12 -> bus.write(de, a)
            0x22 -> {
                bus.write(hl, a)
                hl = (hl + 1) and 0xFFFF
            }
            0x32 -> {
                bus.write(hl, a)
                hl = (hl - 1) and 0xFFFF
            }
            0x0A -> a = bus.read(bc)
            0x1A -> a = bus.read(de)
            0x2A -> {
                a = bus.read(hl)
                hl = (hl + 1) and 0xFFFF
            }
            0x3A -> {
                a = bus.read(hl)
                hl = (hl - 1) and 0xFFFF
            }
            0x03 -> {
                bus.internalCycle()
                bc = (bc + 1) and 0xFFFF
            }
            0x13 -> {
                bus.internalCycle()
                de = (de + 1) and 0xFFFF
            }
            0x23 -> {
                bus.internalCycle()
                hl = (hl + 1) and 0xFFFF
            }
            0x33 -> {
                bus.internalCycle()
                sp = (sp + 1) and 0xFFFF
            }
            0x0B -> {
                bus.internalCycle()
                bc = (bc - 1) and 0xFFFF
            }
            0x1B -> {
                bus.internalCycle()
                de = (de - 1) and 0xFFFF
            }
            0x2B -> {
                bus.internalCycle()
                hl = (hl - 1) and 0xFFFF
            }
            0x3B -> {
                bus.internalCycle()
                sp = (sp - 1) and 0xFFFF
            }
            0x04, 0x0C, 0x14, 0x1C, 0x24, 0x2C, 0x34, 0x3C -> {
                val i = (op shr 3) and 7
                set8(i, inc8(get8(i)))
            }
            0x05, 0x0D, 0x15, 0x1D, 0x25, 0x2D, 0x35, 0x3D -> {
                val i = (op shr 3) and 7
                set8(i, dec8(get8(i)))
            }
            0x06, 0x0E, 0x16, 0x1E, 0x26, 0x2E, 0x36, 0x3E -> set8((op shr 3) and 7, imm8())
            0x07 -> {
                val carry = a shr 7
                a = ((a shl 1) or carry) and 0xFF
                flags(z = false, n = false, h = false, c = carry != 0)
            }
            0x0F -> {
                val carry = a and 1
                a = (a shr 1) or (carry shl 7)
                flags(z = false, n = false, h = false, c = carry != 0)
            }
            0x17 -> {
                val carry = a shr 7
                a = ((a shl 1) or (if (fc) 1 else 0)) and 0xFF
                flags(z = false, n = false, h = false, c = carry != 0)
            }
            0x1F -> {
                val carry = a and 1
                a = (a shr 1) or (if (fc) 0x80 else 0)
                flags(z = false, n = false, h = false, c = carry != 0)
            }
            0x08 -> {
                val addr = imm16()
                bus.write(addr, sp and 0xFF)
                bus.write((addr + 1) and 0xFFFF, (sp shr 8) and 0xFF)
            }
            0x09 -> addHl(bc)
            0x19 -> addHl(de)
            0x29 -> addHl(hl)
            0x39 -> addHl(sp)
            0x10 -> stopped = true
            0x18 -> jr(true)
            0x20, 0x28, 0x30, 0x38 -> jr(cond((op shr 3) and 3))
            0x27 -> daa()
            0x2F -> {
                a = a.inv() and 0xFF
                flags(fz, n = true, h = true, c = fc)
            }
            0x37 -> flags(fz, n = false, h = false, c = true)
            0x3F -> flags(fz, n = false, h = false, c = !fc)
            0x76 -> if (!ime && bus.pendingInterrupts() != 0) haltBug = true else halted = true
            0xC0, 0xC8, 0xD0, 0xD8 -> retCc(cond((op shr 3) and 3))
            0xC1 -> bc = pop()
            0xD1 -> de = pop()
            0xE1 -> hl = pop()
            0xF1 -> {
                val v = pop()
                a = (v shr 8) and 0xFF
                f = v and 0xF0
            }
            0xC2, 0xCA, 0xD2, 0xDA -> jp(cond((op shr 3) and 3))
            0xC3 -> jp(true)
            0xC4, 0xCC, 0xD4, 0xDC -> call(cond((op shr 3) and 3))
            0xC5 -> push(bc)
            0xD5 -> push(de)
            0xE5 -> push(hl)
            0xF5 -> push((a shl 8) or f)
            0xC6, 0xCE, 0xD6, 0xDE, 0xE6, 0xEE, 0xF6, 0xFE -> alu((op shr 3) and 7, imm8())
            0xC7, 0xCF, 0xD7, 0xDF, 0xE7, 0xEF, 0xF7, 0xFF -> {
                push(pc)
                pc = op and 0x38
            }
            0xC9 -> {
                pc = pop()
                bus.internalCycle()
            }
            0xD9 -> {
                pc = pop()
                bus.internalCycle()
                ime = true
            }
            0xCB -> cb()
            0xCD -> call(true)
            0xE0 -> bus.write(0xFF00 or imm8(), a)
            0xF0 -> a = bus.read(0xFF00 or imm8())
            0xE2 -> bus.write(0xFF00 or c, a)
            0xF2 -> a = bus.read(0xFF00 or c)
            0xE8 -> {
                sp = spPlusImm()
                bus.internalCycle()
                bus.internalCycle()
            }
            0xF8 -> {
                hl = spPlusImm()
                bus.internalCycle()
            }
            0xE9 -> pc = hl
            0xEA -> bus.write(imm16(), a)
            0xFA -> a = bus.read(imm16())
            0xF3 -> {
                ime = false
                eiPending = false
                suppressEnable = true
            }
            0xFB -> eiPending = true
            0xF9 -> {
                bus.internalCycle()
                sp = hl
            }
            else -> locked = true // 0xD3, 0xDB, 0xDD, 0xE3, 0xE4, 0xEB, 0xEC, 0xED, 0xF4, 0xFC, 0xFD
        }
    }

    private fun daa() {
        var adjust = 0
        var carry = fc
        if (fh || (!fn && (a and 0xF) > 9)) adjust = adjust or 0x06
        if (fc || (!fn && a > 0x99)) {
            adjust = adjust or 0x60
            carry = true
        }
        a = (if (fn) a - adjust else a + adjust) and 0xFF
        flags(a == 0, fn, h = false, c = carry)
    }

    private fun cb() {
        val cbOp = imm8()
        val i = cbOp and 7
        val bit = (cbOp shr 3) and 7
        when (cbOp shr 6) {
            0 -> set8(i, rotate(bit, get8(i)))
            1 -> {
                val v = get8(i)
                flags((v and (1 shl bit)) == 0, n = false, h = true, c = fc)
            }
            2 -> set8(i, get8(i) and (1 shl bit).inv())
            else -> set8(i, get8(i) or (1 shl bit))
        }
    }

    private fun rotate(kind: Int, v: Int): Int {
        val r: Int
        val carry: Boolean
        when (kind) {
            0 -> {
                r = ((v shl 1) or (v shr 7)) and 0xFF
                carry = v and 0x80 != 0
            }
            1 -> {
                r = (v shr 1) or ((v and 1) shl 7)
                carry = v and 1 != 0
            }
            2 -> {
                r = ((v shl 1) or (if (fc) 1 else 0)) and 0xFF
                carry = v and 0x80 != 0
            }
            3 -> {
                r = (v shr 1) or (if (fc) 0x80 else 0)
                carry = v and 1 != 0
            }
            4 -> {
                r = (v shl 1) and 0xFF
                carry = v and 0x80 != 0
            }
            5 -> {
                r = (v shr 1) or (v and 0x80)
                carry = v and 1 != 0
            }
            6 -> {
                r = ((v shl 4) or (v shr 4)) and 0xFF
                carry = false
            }
            else -> {
                r = v shr 1
                carry = v and 1 != 0
            }
        }
        flags(r == 0, n = false, h = false, c = carry)
        return r
    }
}
