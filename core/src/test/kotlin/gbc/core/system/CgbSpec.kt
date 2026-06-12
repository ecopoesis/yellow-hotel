package gbc.core.system

import gbc.core.cart.parseCartridge
import gbc.fixtures.SyntheticRom
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun cgbSystem(vararg code: Int): SystemState {
    val rom = SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03, cgbFlag = 0x80)
    code.forEachIndexed { i, b -> rom[0x100 + i] = b.toByte() }
    return postBootState(parseCartridge(rom).shouldBeRight())
}

private fun run(s: SystemState, steps: Int): SystemState {
    var state = s
    repeat(steps) { state = stepInstruction(state) }
    return state
}

private val LCD_OFF = intArrayOf(0x3E, 0x00, 0xE0, 0x40)

class CgbSpec : FunSpec({

    test("CGB-flagged carts boot in CGB mode with A=0x11") {
        val s = cgbSystem(0x00)
        s.mode shouldBe HwMode.Cgb
        s.cpu.a shouldBe 0x11
    }

    test("VBK selects the VRAM bank and reads back 0xFE|bank") {
        val s = run(
            cgbSystem(
                *LCD_OFF,
                0x3E, 0x01, 0xE0, 0x4F, // VBK=1
                0x3E, 0x42, 0xEA, 0x00, 0x80, // (0x8000)=0x42 in bank 1
                0x3E, 0x00, 0xE0, 0x4F, // VBK=0
                0x3E, 0x99, 0xEA, 0x00, 0x80, // (0x8000)=0x99 in bank 0
                0xF0, 0x4F, // read VBK
            ),
            11,
        )
        (s.vram[0x2000].toInt() and 0xFF) shouldBe 0x42
        (s.vram[0x0000].toInt() and 0xFF) shouldBe 0x99
        s.cpu.a shouldBe 0xFE
    }

    test("SVBK selects WRAM bank 1-7 at 0xD000, treating 0 as 1") {
        val s = run(
            cgbSystem(
                0x3E, 0x03, 0xE0, 0x70, // SVBK=3
                0x3E, 0x42, 0xEA, 0x00, 0xD0, // (0xD000)=0x42 -> bank 3
                0x3E, 0x00, 0xE0, 0x70, // SVBK=0 -> bank 1
                0x3E, 0x99, 0xEA, 0x00, 0xD0, // (0xD000)=0x99 -> bank 1
                0xF0, 0x70, // read SVBK
            ),
            10,
        )
        (s.wram[3 * 0x1000].toInt() and 0xFF) shouldBe 0x42
        (s.wram[1 * 0x1000].toInt() and 0xFF) shouldBe 0x99
        (s.cpu.a and 0x07) shouldBe 0x00
    }

    test("BCPS/BCPD write palette RAM with auto-increment; OCPS/OCPD likewise") {
        val s = run(
            cgbSystem(
                *LCD_OFF,
                0x3E, 0x80, 0xE0, 0x68, // BCPS = auto-increment, index 0
                0x3E, 0x1F, 0xE0, 0x69, // BCPD = 0x1F (red lo)
                0x3E, 0x00, 0xE0, 0x69, // BCPD = 0x00 (red hi)
                0x3E, 0x81, 0xE0, 0x6A, // OCPS = auto-increment, index 1
                0x3E, 0x7C, 0xE0, 0x6B, // OCPD
            ),
            12,
        )
        (s.ppu.bgPal[0].toInt() and 0xFF) shouldBe 0x1F
        (s.ppu.bgPal[1].toInt() and 0xFF) shouldBe 0x00
        (s.ppu.objPal[1].toInt() and 0xFF) shouldBe 0x7C
        (s.ppu.bcps and 0x3F) shouldBe 2 // auto-incremented twice
        (s.ppu.ocps and 0x3F) shouldBe 2
    }

    test("BCPD reads back the indexed palette byte") {
        val s = run(
            cgbSystem(
                *LCD_OFF,
                0x3E, 0x00, 0xE0, 0x68, // BCPS = index 0, no increment
                0x3E, 0x55, 0xE0, 0x69, // BCPD write
                0xF0, 0x69, // BCPD read
            ),
            7,
        )
        s.cpu.a shouldBe 0x55
    }

    test("KEY1 arms and STOP toggles double speed without stopping the machine") {
        var s = run(
            cgbSystem(
                0xF0, 0x4D, // read KEY1 (normal speed)
            ),
            1,
        )
        s.cpu.a shouldBe 0x7E

        s = run(
            cgbSystem(
                0x3E, 0x01, 0xE0, 0x4D, // arm
                0x10, // STOP -> speed switch
                0xF0, 0x4D, // read KEY1
            ),
            4,
        )
        s.doubleSpeed shouldBe true
        s.cpu.stopped shouldBe false
        s.cpu.a shouldBe 0xFE // bit7 = double speed, armed cleared
    }

    test("the speed switch also applies inside whole-frame stepping") {
        var s = cgbSystem(0x3E, 0x01, 0xE0, 0x4D, 0x10, 0x18, 0xFE) // arm KEY1; STOP; spin
        s = stepFrame(s)
        s.doubleSpeed shouldBe true
        s.cpu.stopped shouldBe false
        s.ppu.frameReady shouldBe false
    }

    test("in double speed the PPU runs at half the dots per M-cycle") {
        var s = run(cgbSystem(0x3E, 0x01, 0xE0, 0x4D, 0x10), 3) // enter double speed
        val dotsBefore = s.ppu.ly * 456 + s.ppu.dot
        val cyclesBefore = s.tCycles
        s = run(s, 100) // 100 NOPs
        val cpuT = s.tCycles - cyclesBefore
        val dots = (s.ppu.ly * 456 + s.ppu.dot) - dotsBefore
        dots * 2 shouldBe cpuT.toInt()
    }

    test("GDMA copies (len+1)x16 bytes to VRAM immediately and reports done") {
        var s = cgbSystem(
            *LCD_OFF,
            0x3E, 0xC1, 0xE0, 0x51, // src hi = 0xC1
            0x3E, 0x00, 0xE0, 0x52, // src lo
            0x3E, 0x01, 0xE0, 0x53, // dst hi -> 0x8100
            0x3E, 0x00, 0xE0, 0x54, // dst lo
            0x3E, 0x01, 0xE0, 0x55, // GDMA, 2 blocks = 32 bytes
            0xF0, 0x55, // read FF55
        )
        for (i in 0 until 32) s.wram[0x100 + i] = (i + 1).toByte()
        s = run(s, 13)
        for (i in 0 until 32) {
            (s.vram[0x100 + i].toInt() and 0xFF) shouldBe (i + 1)
        }
        s.cpu.a shouldBe 0xFF // inactive
    }

    test("HBlank DMA copies 16 bytes per HBlank and can be aborted") {
        var s = cgbSystem(
            0x3E, 0xC2, 0xE0, 0x51,
            0x3E, 0x00, 0xE0, 0x52,
            0x3E, 0x02, 0xE0, 0x53, // dst 0x8200
            0x3E, 0x00, 0xE0, 0x54,
            0x3E, 0x83, 0xE0, 0x55, // HDMA, 4 blocks
            0x18, 0xFE, // spin
        )
        for (i in 0 until 64) s.wram[0x200 + i] = 0x5A
        s = run(s, 12)
        // ~2 scanlines: two HBlanks -> 2 of 4 blocks copied, transfer still active
        repeat(80) { s = stepInstruction(s) }
        (s.vram[0x200].toInt() and 0xFF) shouldBe 0x5A
        (s.vram[0x210].toInt() and 0xFF) shouldBe 0x5A
        s.hdma.active shouldBe true
        // plenty more scanlines: all 4 blocks done, transfer reports complete
        repeat(300) { s = stepInstruction(s) }
        (s.vram[0x230].toInt() and 0xFF) shouldBe 0x5A
        s.hdma.active shouldBe false
        peek(s, 0xFF55) shouldBe 0xFF
    }

    test("aborting HBlank DMA freezes progress and reports the abort in FF55") {
        var s = cgbSystem(
            0x3E, 0xC2, 0xE0, 0x51,
            0x3E, 0x00, 0xE0, 0x52,
            0x3E, 0x03, 0xE0, 0x53,
            0x3E, 0x00, 0xE0, 0x54,
            0x3E, 0x87, 0xE0, 0x55, // HDMA, 8 blocks
            0x3E, 0x00, 0xE0, 0x55, // abort immediately
            0xF0, 0x55, // read FF55
        )
        s = run(s, 15)
        s.hdma.active shouldBe false
        s.cpu.a shouldBe (0x80 or 7) // bit7 = stopped, 7 blocks were left
    }

    test("HDMA setup registers are write-only; index/OPRI registers read back") {
        var s = run(cgbSystem(0xF0, 0x51), 1)
        s.cpu.a shouldBe 0xFF
        s = run(cgbSystem(0xF0, 0x53), 1)
        s.cpu.a shouldBe 0xFF
        // BCPS/OCPS read with bit 6 set; OCPD reads the indexed byte
        s = run(cgbSystem(0x3E, 0x05, 0xE0, 0x68, 0xF0, 0x68), 3)
        s.cpu.a shouldBe (0x40 or 0x05)
        s = run(
            cgbSystem(
                0x3E, 0x02, 0xE0, 0x6A, // OCPS = index 2
                0x3E, 0x33, 0xE0, 0x6B, // OCPD write
                0x3E, 0x02, 0xE0, 0x6A, // reset index (no auto-increment set, but be explicit)
                0xF0, 0x6B, // OCPD read
            ),
            7,
        )
        s.cpu.a shouldBe 0x33
        s = run(cgbSystem(0xF0, 0x6A), 1)
        s.cpu.a shouldBe 0x40 // fresh OCPS index 0, bit 6 always set
    }

    test("OPRI selects DMG-style sprite priority and reads back") {
        val s = run(cgbSystem(0x3E, 0x01, 0xE0, 0x6C, 0xF0, 0x6C), 3)
        s.ppu.opri shouldBe 1
        s.cpu.a shouldBe 0xFF // 0xFE | 1
    }

    test("CGB-only registers stay open-bus in DMG mode") {
        val rom = SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03)
        rom[0x100] = 0xF0.toByte() // LDH A,(0x4F)
        rom[0x101] = 0x4F.toByte()
        val s = stepInstruction(postBootState(parseCartridge(rom).shouldBeRight(), HwMode.Dmg))
        s.cpu.a shouldBe 0xFF
    }
})
