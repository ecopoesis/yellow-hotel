package gbc.core.apu

import gbc.core.cart.parseCartridge
import gbc.core.system.HwMode
import gbc.core.system.SystemState
import gbc.core.system.postBootState
import gbc.core.system.stepInstruction
import gbc.fixtures.SyntheticRom
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

private fun system(vararg code: Int): SystemState {
    val rom = SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03)
    code.forEachIndexed { i, b -> rom[0x100 + i] = b.toByte() }
    return postBootState(parseCartridge(rom).shouldBeRight(), HwMode.Dmg)
}

class ApuSpec : FunSpec({

    test("a triggered square channel produces samples that takeSamples drains in order") {
        var s = system(
            0x3E, 0xF0, 0xE0, 0x12, // NR12: full volume, no envelope -> DAC on
            0x3E, 0x00, 0xE0, 0x13, // NR13: freq lo
            0x3E, 0x87, 0xE0, 0x14, // NR14: trigger, freq hi=7 (high pitch)
            0x18, 0xFE, // spin
        )
        repeat(3000) { s = stepInstruction(s) }
        s.apu.ch1.enabled.shouldBeTrue()
        s.apu.sampleCount shouldBeGreaterThan 50

        val before = s.apu.sampleCount
        val out = FloatArray(64 * 2)
        val (drained, taken) = takeSamples(s.apu, out, 64)
        taken shouldBe 64
        drained.sampleCount shouldBe before - 64
        // a square wave at full volume must produce some non-silent samples
        out.any { it != 0f }.shouldBeTrue()

        // draining more than available returns only what exists
        val (empty, rest) = takeSamples(drained, FloatArray(65536), 32768)
        rest shouldBe before - 64
        empty.sampleCount shouldBe 0
    }

    test("unused APU addresses read 0xFF") {
        var s = system(0xF0, 0x15) // LDH A,(FF15)
        s = stepInstruction(s)
        s.cpu.a shouldBe 0xFF
        s = system(0xF0, 0x1F)
        s = stepInstruction(s)
        s.cpu.a shouldBe 0xFF
    }

    test("apu state uses identity equality") {
        val a = ApuState()
        val b = ApuState()
        (a == a) shouldBe true
        (a == b) shouldBe false
        a.hashCode() shouldBe a.hashCode()
    }
})
