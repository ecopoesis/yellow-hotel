package gbc.core.serial

import gbc.core.api.Ports
import gbc.core.api.SerialOut
import gbc.core.cart.parseCartridge
import gbc.core.system.HwMode
import gbc.core.system.SystemState
import gbc.core.system.postBootState
import gbc.core.system.stepInstruction
import gbc.fixtures.SyntheticRom
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun system(vararg code: Int): SystemState {
    val rom = SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03)
    code.forEachIndexed { i, b -> rom[0x100 + i] = b.toByte() }
    return postBootState(parseCartridge(rom).shouldBeRight(), HwMode.Dmg)
}

class SerialSpec : FunSpec({

    test("an internally-clocked transfer shifts 8 bits at 8192 Hz and completes after 128x8 M-cycles") {
        val sent = mutableListOf<Int>()
        val ports = Ports(serial = SerialOut { sent += it })
        var s = system(
            0x3E, 0x69, // LD A,0x69
            0xE0, 0x01, // LDH (SB),A
            0x3E, 0x81, // LD A,0x81
            0xE0, 0x02, // LDH (SC),A
            0x18, 0xFE, // JR -2 spin
        )
        repeat(4) { s = stepInstruction(s, ports) }
        val startCycles = s.tCycles
        sent shouldBe emptyList() // nothing emitted yet: the transfer is clocked

        while ((s.intr.iff and 0x08) == 0 && s.tCycles < startCycles + 10_000) {
            s = stepInstruction(s, ports)
        }
        sent shouldBe listOf(0x69)
        s.serial.data shouldBe 0xFF // 0xFF shifts in with no link partner
        (s.serial.ctrl and 0x80) shouldBe 0 // transfer-in-progress bit cleared
        // 8 bits at 512 T-cycles each = 4096 T-cycles, +/- one instruction
        val elapsed = s.tCycles - startCycles
        (elapsed in 4096..4136) shouldBe true
    }

    test("an externally-clocked transfer never completes on its own") {
        val sent = mutableListOf<Int>()
        val ports = Ports(serial = SerialOut { sent += it })
        var s = system(
            0x3E, 0x69,
            0xE0, 0x01,
            0x3E, 0x80, // SC = start + external clock
            0xE0, 0x02,
            0x18, 0xFE,
        )
        repeat(2000) { s = stepInstruction(s, ports) }
        sent shouldBe emptyList()
        (s.serial.ctrl and 0x80) shouldBe 0x80 // still "in progress"
        (s.intr.iff and 0x08) shouldBe 0
    }
})
