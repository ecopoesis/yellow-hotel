package gbc.core.dma

import gbc.core.cart.parseCartridge
import gbc.core.system.HwMode
import gbc.core.system.SystemState
import gbc.core.system.postBootState
import gbc.core.system.stepInstruction
import gbc.fixtures.SyntheticRom
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * During OAM DMA the CPU can only really use HRAM — including instruction
 * fetches — so every probe routine here runs from HRAM, exactly like real
 * games' DMA wait loops. ROM entry is JP 0xFF80.
 */
private fun systemWithHramRoutine(vararg routine: Int): SystemState {
    val rom = SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03)
    listOf(0xC3, 0x80, 0xFF).forEachIndexed { i, b -> rom[0x100 + i] = b.toByte() } // JP 0xFF80
    val s = postBootState(parseCartridge(rom).shouldBeRight(), HwMode.Dmg)
    routine.forEachIndexed { i, b -> s.hram[i] = b.toByte() }
    return s
}

private fun run(s: SystemState, steps: Int): SystemState {
    var state = s
    repeat(steps) { state = stepInstruction(state) }
    return state
}

private fun runUntilDmaDone(start: SystemState, maxSteps: Int = 400): SystemState {
    var s = start
    var steps = 0
    while (s.dma.active && steps++ < maxSteps) s = stepInstruction(s)
    s.dma.active shouldBe false
    return s
}

private val START_DMA_C0 = intArrayOf(0x3E, 0xC0, 0xE0, 0x46) // LD A,0xC0; LDH (FF46),A
private val SPIN = intArrayOf(0x18, 0xFE) // JR -2

class OamDmaSpec : FunSpec({

    test("OAM DMA copies 160 bytes from the source page") {
        var s = systemWithHramRoutine(*START_DMA_C0, *SPIN)
        for (i in 0 until 160) s.wram[i] = (i + 1).toByte()
        s = run(s, 3) // JP; LD; LDH
        s.dma.active shouldBe true
        s = runUntilDmaDone(s)
        for (i in 0 until 160) {
            (s.oam[i].toInt() and 0xFF) shouldBe (i + 1)
        }
    }

    test("FF46 reads back the last value written") {
        var s = systemWithHramRoutine(
            *START_DMA_C0,
            0xF0, 0x46, // LDH A,(FF46)
            *SPIN,
        )
        s = run(s, 4)
        s.cpu.a shouldBe 0xC0
    }

    test("the CPU sees 0xFF when reading OAM during DMA") {
        var s = systemWithHramRoutine(
            *START_DMA_C0,
            0xFA, 0x10, 0xFE, // LD A,(0xFE10) during DMA
            *SPIN,
        )
        s.oam[0x10] = 0x55
        s = run(s, 4)
        s.cpu.a shouldBe 0xFF
    }

    test("external-bus reads during external-source DMA return the byte DMA is transferring") {
        var s = systemWithHramRoutine(
            *START_DMA_C0,
            0xFA, 0x50, 0x01, // LD A,(0x0150): ROM read while DMA reads WRAM
            *SPIN,
        )
        for (i in 0 until 160) s.wram[i] = 0x77
        s = run(s, 4)
        s.cpu.a shouldBe 0x77
    }

    test("VRAM reads are unaffected while DMA runs from the external bus") {
        var s = systemWithHramRoutine(
            *START_DMA_C0,
            0xFA, 0x23, 0x81, // LD A,(0x8123)
            *SPIN,
        )
        s.vram[0x123] = 0x5A
        for (i in 0 until 160) s.wram[i] = 0x77
        s = run(s, 4)
        s.cpu.a shouldBe 0x5A
    }

    test("HRAM stays accessible during DMA") {
        var s = systemWithHramRoutine(
            0x3E, 0x42, // LD A,0x42
            0xE0, 0xB0, // LDH (FFB0),A
            *START_DMA_C0,
            0xF0, 0xB0, // LDH A,(FFB0) during DMA
            *SPIN,
        )
        s = run(s, 6)
        s.cpu.a shouldBe 0x42
    }

    test("sources at 0xE0..0xFF mirror down to WRAM") {
        var s = systemWithHramRoutine(0x3E, 0xE0, 0xE0, 0x46, *SPIN) // DMA from 0xE0 = echo of 0xC0
        for (i in 0 until 160) s.wram[i] = 0x33
        s = run(s, 3)
        s = runUntilDmaDone(s)
        (s.oam[0].toInt() and 0xFF) shouldBe 0x33
    }

    test("restarting DMA while active begins a fresh transfer from the new page") {
        var s = systemWithHramRoutine(
            *START_DMA_C0,
            0x3E, 0xD0,
            0xE0, 0x46, // restart from 0xD0 mid-flight
            *SPIN,
        )
        for (i in 0 until 160) s.wram[i] = 0x11
        for (i in 0 until 160) s.wram[0x1000 + i] = 0x22
        s = run(s, 5)
        s = runUntilDmaDone(s)
        (s.oam[0].toInt() and 0xFF) shouldBe 0x22
        (s.oam[159].toInt() and 0xFF) shouldBe 0x22
    }

    test("OAM writes during DMA are ignored") {
        var s = systemWithHramRoutine(
            *START_DMA_C0,
            0x3E, 0x99,
            0xEA, 0x20, 0xFE, // LD (0xFE20),A during DMA
            *SPIN,
        )
        for (i in 0 until 160) s.wram[i] = 0x44
        s = run(s, 5)
        s = runUntilDmaDone(s)
        (s.oam[0x20].toInt() and 0xFF) shouldBe 0x44
    }

    test("DMA occupies at least 160 M-cycles of transfer time") {
        var s = systemWithHramRoutine(*START_DMA_C0, *SPIN)
        s = run(s, 3)
        val start = s.tCycles
        s = runUntilDmaDone(s)
        ((s.tCycles - start) / 4).toInt() shouldBeGreaterThanOrEqual 160
    }
})
