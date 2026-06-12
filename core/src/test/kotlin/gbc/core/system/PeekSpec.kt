package gbc.core.system

import gbc.core.cart.parseCartridge
import gbc.fixtures.SyntheticRom
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * peek() is the side-effect-free debugger/harness view of the bus. The CPU
 * uses its own live path, so peek's decode is pinned here directly.
 */
class PeekSpec : FunSpec({

    val cgb = postBootState(
        parseCartridge(SyntheticRom.banked(0x05, 0x1B, 0x03, cgbFlag = 0x80)).shouldBeRight(),
    )
    val dmg = postBootState(
        parseCartridge(SyntheticRom.banked(0x05, 0x1B, 0x03)).shouldBeRight(),
        HwMode.Dmg,
    )

    test("IE reads through 0xFFFF") {
        peek(cgb.copy(intr = cgb.intr.copy(ie = 0xAB)), 0xFFFF) shouldBe 0xAB
    }

    test("CGB registers peek their live values in CGB mode and 0xFF on DMG") {
        peek(cgb, 0xFF4D) shouldBe 0x7E // KEY1: normal speed, not armed
        peek(cgb.copy(doubleSpeed = true, key1Armed = true), 0xFF4D) shouldBe 0xFF
        peek(cgb, 0xFF4F) shouldBe 0xFE // VBK bank 0
        peek(cgb, 0xFF51) shouldBe 0xFF // HDMA setup write-only
        peek(cgb, 0xFF55) shouldBe 0xFF // no transfer ever started
        peek(cgb, 0xFF68) shouldBe 0x40 // BCPS index 0
        peek(cgb, 0xFF69) shouldBe 0xFF // palette RAM boots white
        peek(cgb, 0xFF6A) shouldBe 0x40
        peek(cgb, 0xFF6B) shouldBe 0xFF
        peek(cgb, 0xFF6C) shouldBe 0xFE // OPRI 0
        peek(cgb, 0xFF70) shouldBe 0xF9 // SVBK boots at bank 1

        for (reg in listOf(0xFF4D, 0xFF4F, 0xFF55, 0xFF68, 0xFF69, 0xFF6A, 0xFF6B, 0xFF6C, 0xFF70)) {
            peek(dmg, reg) shouldBe 0xFF
        }
    }

    test("unmapped registers peek as open bus") {
        peek(dmg, 0xFF03) shouldBe 0xFF
        peek(cgb, 0xFF7F) shouldBe 0xFF
    }
})
