package gbc.core.system

import gbc.core.api.Ports
import gbc.core.api.SerialOut
import gbc.core.cart.MbcState
import gbc.core.cart.parseCartridge
import gbc.fixtures.SyntheticRom
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Boots a synthetic MBC5 cart with [code] placed at the entry point (0x100).
 * The header checksum only covers 0x134..0x14C, so this area is free.
 */
private fun systemWith(vararg code: Int): SystemState {
    val rom = SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03)
    code.forEachIndexed { i, b -> rom[0x100 + i] = b.toByte() }
    return postBootState(parseCartridge(rom).shouldBeRight(), HwMode.Dmg)
}

private fun run(s: SystemState, steps: Int, ports: Ports = Ports.NONE): SystemState {
    var state = s
    repeat(steps) { state = stepInstruction(state, ports) }
    return state
}

class BusSpec : FunSpec({

    test("post-boot state honours the cart's CGB flag by default") {
        val dual = parseCartridge(SyntheticRom.banked(0x05, 0x1B, 0x03, cgbFlag = 0x80)).shouldBeRight()
        postBootState(dual).mode shouldBe HwMode.Cgb
        postBootState(dual).cpu.a shouldBe 0x11
        val dmg = parseCartridge(SyntheticRom.banked(0x05, 0x1B, 0x03)).shouldBeRight()
        postBootState(dmg).mode shouldBe HwMode.Dmg
        postBootState(dmg).cpu.a shouldBe 0x01
    }

    test("each CPU M-cycle advances machine time by 4 T-cycles") {
        val s = run(systemWith(0x00), 1) // NOP: 1 M-cycle
        s.tCycles shouldBe 4
    }

    test("WRAM, HRAM, VRAM, and OAM are readable and writable through the bus") {
        // LCD off first so VRAM/OAM are not mode-locked; then write/read each region.
        for (addr in listOf(0xC123, 0xD456, 0x8123, 0xFE32, 0xFF85)) {
            val s = run(
                systemWith(
                    0x3E, 0x00, // LD A,0
                    0xE0, 0x40, // LDH (LCDC),A: LCD off
                    0x3E, 0x42, // LD A,0x42
                    0xEA, addr and 0xFF, addr shr 8, // LD (addr),A
                    0x3E, 0x00, // LD A,0
                    0xFA, addr and 0xFF, addr shr 8, // LD A,(addr)
                ),
                6,
            )
            s.cpu.a shouldBe 0x42
        }
    }

    test("VRAM reads 0xFF and swallows writes during mode 3; OAM during modes 2 and 3") {
        // With the LCD on at boot (mode 2, dot 0), OAM is locked immediately.
        val s = run(
            systemWith(
                0x3E, 0x42, // LD A,0x42
                0xEA, 0x32, 0xFE, // LD (0xFE32),A -> swallowed (mode 2)
                0xFA, 0x32, 0xFE, // LD A,(0xFE32) -> 0xFF
            ),
            3,
        )
        s.cpu.a shouldBe 0xFF
        (s.oam[0x32].toInt() and 0xFF) shouldBe 0x00
    }

    test("echo RAM mirrors WRAM both ways") {
        val s = run(
            systemWith(
                0x3E, 0x99, // LD A,0x99
                0xEA, 0x00, 0xE0, // LD (0xE000),A  -> lands in WRAM 0xC000
                0xFA, 0x00, 0xC0, // LD A,(0xC000)
            ),
            3,
        )
        s.cpu.a shouldBe 0x99
        (s.wram[0].toInt() and 0xFF) shouldBe 0x99
    }

    test("the prohibited 0xFEA0-0xFEFF region reads 0 and swallows writes") {
        val s = run(
            systemWith(
                0x3E, 0x55, // LD A,0x55
                0xEA, 0xA5, 0xFE, // LD (0xFEA5),A
                0xFA, 0xA5, 0xFE, // LD A,(0xFEA5)
            ),
            3,
        )
        s.cpu.a shouldBe 0x00
    }

    test("IF reads with the upper 3 bits set; IE stores all 8 bits") {
        val s = run(
            systemWith(
                0x3E, 0x05, // LD A,0x05
                0xE0, 0x0F, // LDH (0xFF0F),A
                0x3E, 0xAB, // LD A,0xAB
                0xEA, 0xFF, 0xFF, // LD (0xFFFF),A
                0xF0, 0x0F, // LDH A,(0xFF0F)
            ),
            5,
        )
        s.cpu.a shouldBe 0xE5
        s.intr.ie shouldBe 0xAB
        s.intr.iff shouldBe 0x05
    }

    test("unclaimed-but-existing IO registers act as raw bytes until their component exists") {
        val s = run(
            systemWith(
                0x3E, 0x7B, // LD A,0x7B
                0xE0, 0x47, // LDH (0xFF47),A  (BGP)
                0x3E, 0x00,
                0xF0, 0x47, // LDH A,(0xFF47)
            ),
            4,
        )
        s.cpu.a shouldBe 0x7B
    }

    test("nonexistent IO registers read 0xFF on DMG (open bus): KEY1 and register gaps") {
        for (reg in listOf(0x4D, 0x03, 0x4F, 0x70)) {
            val s = run(
                systemWith(
                    0x3E, 0x00, // LD A,0
                    0xE0, reg, // LDH (FF00+reg),A — swallowed
                    0xF0, reg, // LDH A,(FF00+reg)
                ),
                3,
            )
            s.cpu.a shouldBe 0xFF
        }
    }

    test("ROM-area writes reach the MBC: bank switch visible through the bus") {
        val s = run(
            systemWith(
                0x3E, 0x05, // LD A,5
                0xEA, 0x00, 0x20, // LD (0x2000),A  -> ROM bank 5
                0xFA, 0x50, 0x41, // LD A,(0x4150)  -> bank stamp low byte
            ),
            3,
        )
        s.cpu.a shouldBe 5
        s.cart.mbc.shouldBeInstanceOf<MbcState.Mbc5>().romBank shouldBe 5
    }

    test("an internally-clocked serial transfer emits the byte, requests the interrupt, and shifts in 0xFF") {
        val sent = mutableListOf<Int>()
        // start the transfer, then idle on NOPs while it clocks out (8 bits x 512 T)
        val s = run(
            systemWith(
                0x3E, 0x69, // LD A,0x69
                0xE0, 0x01, // LDH (FF01),A
                0x3E, 0x81, // LD A,0x81
                0xE0, 0x02, // LDH (FF02),A
            ),
            4 + 1200,
            Ports(serial = SerialOut { sent += it }),
        )
        sent shouldBe listOf(0x69)
        s.serial.data shouldBe 0xFF
        s.serial.ctrl and 0x80 shouldBe 0
        (s.intr.iff and 0x08) shouldBe 0x08
    }

    test("writing FF02 without the start bit just stores the control value") {
        val sent = mutableListOf<Int>()
        val s = run(
            systemWith(
                0x3E, 0x01, // LD A,0x01 (external clock selected, no start)
                0xE0, 0x02, // LDH (FF02),A
            ),
            2,
            Ports(serial = SerialOut { sent += it }),
        )
        sent shouldBe emptyList()
        s.serial.ctrl shouldBe 0x01
    }

    test("timer registers are reachable through the bus") {
        var s = run(
            systemWith(
                0x3E, 0x42, // LD A,0x42
                0xE0, 0x06, // LDH (TMA),A
                0xF0, 0x06, // LDH A,(TMA)
            ),
            3,
        )
        s.cpu.a shouldBe 0x42

        s = run(
            systemWith(
                0xE0, 0x04, // LDH (DIV),A: reset
                0xF0, 0x04, // LDH A,(DIV): a few cycles later, still 0
            ),
            2,
        )
        s.cpu.a shouldBe 0x00

        run(systemWith(0xF0, 0x05), 1).cpu.a shouldBe 0x00 // TIMA
        run(systemWith(0xF0, 0x07), 1).cpu.a shouldBe 0xF8 // TAC reads with upper bits set
    }

    test("serial registers are reachable through the bus") {
        val s = run(
            systemWith(
                0x3E, 0x33, // LD A,0x33
                0xE0, 0x01, // LDH (SB),A
                0xF0, 0x01, // LDH A,(SB)
            ),
            3,
        )
        s.cpu.a shouldBe 0x33
        run(systemWith(0xF0, 0x02), 1).cpu.a shouldBe 0x7E // SC unused bits high
    }

    test("PPU registers read back through the bus") {
        // write 0x42 to each writable PPU register, read it back
        for (reg in listOf(0x42, 0x43, 0x45, 0x47, 0x48, 0x49, 0x4A, 0x4B)) {
            val s = run(
                systemWith(
                    0x3E, 0x42, // LD A,0x42
                    0xE0, reg, // LDH (reg),A
                    0x3E, 0x00,
                    0xF0, reg, // LDH A,(reg)
                ),
                4,
            )
            s.cpu.a shouldBe 0x42
        }
        // LY is read-only: writes are ignored
        val s = run(systemWith(0x3E, 0x42, 0xE0, 0x44, 0xF0, 0x44), 3)
        s.cpu.a shouldBe s.ppu.ly
    }

    test("IE reads back through 0xFFFF") {
        val s = run(
            systemWith(
                0x3E, 0xAB, // LD A,0xAB
                0xEA, 0xFF, 0xFF, // LD (0xFFFF),A
                0x3E, 0x00,
                0xFA, 0xFF, 0xFF, // LD A,(0xFFFF)
            ),
            4,
        )
        s.cpu.a shouldBe 0xAB
    }

    test("stepFrame runs to the next completed frame and consumes the ready flag") {
        val s = stepFrame(systemWith(0x18, 0xFE)) // spin loop, default ports
        s.ppu.frameReady shouldBe false
        s.ppu.ly shouldBe 144 // a frame completes when VBlank begins
        // 144 lines x 456 dots, plus the tail of the instruction that crossed the boundary
        (s.tCycles - 65664L in 0..16) shouldBe true
    }

    test("system state uses identity equality") {
        val a = systemWith(0x00)
        val b = systemWith(0x00)
        (a == a) shouldBe true
        (a == b) shouldBe false
        a.hashCode() shouldBe a.hashCode()
    }
})
