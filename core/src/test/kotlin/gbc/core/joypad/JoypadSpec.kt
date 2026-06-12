package gbc.core.joypad

import gbc.core.api.Button
import gbc.core.cart.parseCartridge
import gbc.core.system.HwMode
import gbc.core.system.SystemState
import gbc.core.system.postBootState
import gbc.core.system.stepInstruction
import gbc.core.system.withButtons
import gbc.fixtures.SyntheticRom
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun system(vararg code: Int): SystemState {
    val rom = SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03)
    code.forEachIndexed { i, b -> rom[0x100 + i] = b.toByte() }
    return postBootState(parseCartridge(rom).shouldBeRight(), HwMode.Dmg)
}

private fun readP1(s: SystemState, select: Int): Int {
    var state = s
    state = stepInstruction(state) // LD A,select
    state = stepInstruction(state) // LDH (P1),A
    state = stepInstruction(state) // LDH A,(P1)
    return state.cpu.a
}

private fun p1Program(select: Int) = intArrayOf(0x3E, select, 0xE0, 0x00, 0xF0, 0x00)

class JoypadSpec : FunSpec({

    test("with no group selected the low nibble reads 0xF") {
        readP1(system(*p1Program(0x30)), 0x30) shouldBe 0xFF // 0xC0 | 0x30 | 0xF
    }

    test("selecting directions reports pressed directions as low bits") {
        val s = withButtons(system(*p1Program(0x20)), Button.RIGHT or Button.DOWN)
        readP1(s, 0x20) shouldBe (0xC0 or 0x20 or 0b0110) // Right(0) and Down(3) low
    }

    test("selecting buttons reports pressed buttons as low bits") {
        val s = withButtons(system(*p1Program(0x10)), Button.A or Button.START)
        readP1(s, 0x10) shouldBe (0xC0 or 0x10 or 0b0110) // A(0) and Start(3) low
    }

    test("a button in the unselected group does not affect the nibble") {
        val s = withButtons(system(*p1Program(0x20)), Button.A)
        readP1(s, 0x20) shouldBe (0xC0 or 0x20 or 0xF)
    }

    test("a new press requests the joypad interrupt when its group is selected") {
        var s = system(0x3E, 0x10, 0xE0, 0x00) // select buttons group
        s = stepInstruction(stepInstruction(s))
        s = withButtons(s, Button.A)
        (s.intr.iff and 0x10) shouldBe 0x10
    }

    test("a new press with the group deselected requests nothing") {
        var s = system(0x3E, 0x30, 0xE0, 0x00) // nothing selected
        s = stepInstruction(stepInstruction(s))
        s = withButtons(s, Button.A)
        (s.intr.iff and 0x10) shouldBe 0
    }

    test("releasing buttons never requests an interrupt") {
        var s = system(0x3E, 0x10, 0xE0, 0x00)
        s = stepInstruction(stepInstruction(s))
        s = withButtons(s, Button.A)
        s = s.copy(intr = s.intr.copy(iff = 0))
        s = withButtons(s, 0)
        (s.intr.iff and 0x10) shouldBe 0
    }

    test("a button press wakes a stopped CPU") {
        var s = system(0x10) // STOP
        s = stepInstruction(s)
        s.cpu.stopped shouldBe true
        s = withButtons(s, Button.START)
        s.cpu.stopped shouldBe false
    }
})
