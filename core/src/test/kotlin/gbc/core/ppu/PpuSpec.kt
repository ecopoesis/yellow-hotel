package gbc.core.ppu

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

private val vram = ByteArray(0x4000)
private val oam = ByteArray(0xA0)

/** Advance [dots] dots, collecting IRQ edges. */
private fun PpuState.advance(dots: Int, v: ByteArray = vram, o: ByteArray = oam): Triple<PpuState, Boolean, Boolean> {
    var p = this
    var vbl = false
    var stat = false
    var left = dots
    while (left > 0) {
        val step = minOf(left, 4)
        val r = ppuTick(p, v, o, step)
        p = r.ppu
        vbl = vbl || r.irqVblank
        stat = stat || r.irqStat
        left -= step
    }
    return Triple(p, vbl, stat)
}

/** Renders one full scanline (line 0) and returns the frame buffer. */
private fun render(start: PpuState, v: ByteArray, o: ByteArray = oam): IntArray {
    var p = start
    repeat(114) { p = ppuTick(p, v, o, 4).ppu }
    return p.frame
}

class PpuSpec : FunSpec({

    test("a scanline is 456 dots and LY increments at its end") {
        var p = PpuState(ly = 0, dot = 0)
        p = p.advance(455).first
        p.ly shouldBe 0
        p = p.advance(1).first
        p.ly shouldBe 1
    }

    test("a frame is 154 lines; LY wraps to 0") {
        var p = PpuState(ly = 0, dot = 0)
        p = p.advance(456 * 154).first
        p.ly shouldBe 0
    }

    test("mode sequence on a visible line: OAM scan (80 dots) then transfer then HBlank") {
        var p = PpuState(ly = 0, dot = 0)
        p.mode shouldBe PpuMode.OamScan
        p = p.advance(81).first // dot 80 is the first transfer dot
        p.mode shouldBe PpuMode.Transfer
        p = p.advance(290).first // transfer is ~172-289 dots; by dot 371 we must be in HBlank
        p.mode shouldBe PpuMode.HBlank
    }

    test("VBlank starts at line 144 with the VBlank interrupt and a ready frame") {
        var p = PpuState(ly = 143, dot = 0, mode = PpuMode.OamScan)
        val (next, vbl, _) = p.advance(456 + 4) // through the wrap into line 144's first dots
        next.ly shouldBe 144
        next.mode shouldBe PpuMode.VBlank
        vbl.shouldBeTrue()
        next.frameReady.shouldBeTrue()
    }

    test("LYC=LY raises a STAT interrupt when enabled, on the rising edge only") {
        var p = PpuState(ly = 9, dot = 0, lyc = 10, statEnables = 0x40, mode = PpuMode.HBlank)
        val (atLine10, _, stat) = p.advance(456)
        atLine10.ly shouldBe 10
        stat.shouldBeTrue()
        // staying on line 10: no second edge
        atLine10.advance(100).third shouldBe false
    }

    test("STAT blocking: a mode-0 condition while the LYC line is already high fires nothing") {
        // enable both LYC and HBlank sources; LYC holds the line high through the whole line
        var p = PpuState(ly = 9, dot = 0, lyc = 10, statEnables = 0x48, mode = PpuMode.HBlank)
        var (p2, _, statEdge) = p.advance(456) // enter line 10: LYC edge fires
        statEdge.shouldBeTrue()
        // run through line 10 into HBlank: line already high -> blocked, no second IRQ
        var stat2 = false
        repeat(110) { // 440 dots: well into HBlank
            val r = ppuTick(p2, vram, oam, 4)
            p2 = r.ppu
            stat2 = stat2 || r.irqStat
        }
        stat2 shouldBe false
    }

    test("HBlank STAT interrupt fires on entering mode 0 when enabled") {
        var p = PpuState(ly = 5, dot = 0, statEnables = 0x08, mode = PpuMode.OamScan)
        val (_, _, stat) = p.advance(400)
        stat.shouldBeTrue()
    }

    test("OAM-scan STAT interrupt fires on entering mode 2") {
        var p = PpuState(ly = 5, dot = 400, statEnables = 0x20, mode = PpuMode.HBlank)
        val (next, _, stat) = p.advance(80)
        next.ly shouldBe 6
        stat.shouldBeTrue()
    }

    test("VBlank STAT interrupt fires on entering mode 1") {
        var p = PpuState(ly = 143, dot = 400, statEnables = 0x10, mode = PpuMode.HBlank)
        p.advance(80).third.shouldBeTrue()
    }

    test("statRead reports mode, LYC flag, enables, and a high bit 7") {
        val p = PpuState(ly = 10, lyc = 10, statEnables = 0x40, mode = PpuMode.Transfer)
        statRead(p) shouldBe (0x80 or 0x40 or 0x04 or 0x03)
    }

    test("SCX fine scroll discards the first scx mod 8 pixels") {
        val v = ByteArray(0x4000)
        // tile 1: left half color 3, right half color 0; tilemap row 0 all tile 1
        for (row in 0..7) {
            v[16 + row * 2] = 0xF0.toByte()
            v[16 + row * 2 + 1] = 0xF0.toByte()
        }
        for (tx in 0..31) v[0x1800 + tx] = 1
        val frame = render(PpuState(lcdc = 0x91, bgp = 0xE4, scx = 3, ly = 0, dot = 0), v)
        // with scx=3 the pattern 33330000 starts 3 pixels in: 30000333 30000...
        frame[0] shouldBe 0x000000 // color 3
        frame[1] shouldBe 0xFFFFFF // color 0
        frame[4] shouldBe 0xFFFFFF
        frame[5] shouldBe 0x000000
    }

    test("a drained sprite FIFO holding a behind-BG pixel in slot 7 stays clean (ushr regression)") {
        val v = ByteArray(0x4000)
        val o = ByteArray(0xA0)
        // tile 1 solid color 3 for sprites
        for (row in 0..7) {
            v[16 + row * 2] = 0xFF.toByte()
            v[16 + row * 2 + 1] = 0xFF.toByte()
        }
        // sprite A: behind-BG (attr bit 7) at x=16; sprite B: in front at x=32
        o[0] = 16; o[1] = 16; o[2] = 1; o[3] = 0x80.toByte()
        o[4] = 16; o[5] = 32; o[6] = 1; o[7] = 0x00
        val frame = render(PpuState(lcdc = 0x93, bgp = 0xE4, obp0 = 0xE4, ly = 0, dot = 0), v, o)
        // BG is color 0 everywhere -> behind-BG sprite A visible at 8..15
        frame[8] shouldBe 0x000000
        // sprite B at 24..31 must render even though A's slot-7 pixel set the sign bit
        frame[24] shouldBe 0x000000
        frame[20] shouldBe 0xFFFFFF // gap between them: BG color 0
    }

    test("left-edge sprites obey X priority, not OAM order (invisible-NPC regression)") {
        // Two sprites both clipped at the left edge overlap screen pixel 0.
        // On DMG the smaller-X sprite wins even though it sits later in OAM.
        // Both clip to trigger point lx=0, so the pipeline must compare X
        // explicitly here — otherwise the low-index far sprite hides the near
        // one (the overworld "invisible NPC" against the player/Pikachu block).
        val v = ByteArray(0x4000)
        val o = ByteArray(0xA0)
        // tile 1: solid color 3 (both planes); tile 2: solid color 1 (low plane)
        for (row in 0..7) {
            v[16 + row * 2] = 0xFF.toByte()
            v[16 + row * 2 + 1] = 0xFF.toByte()
            v[32 + row * 2] = 0xFF.toByte()
        }
        // sprite 0 (low index) at x=8: covers screen 0..7, uses OBP0
        o[0] = 16; o[1] = 8; o[2] = 1; o[3] = 0x00
        // sprite 1 (high index) at x=1: covers only screen 0, smaller X -> wins there
        o[4] = 16; o[5] = 1; o[6] = 2; o[7] = 0x00
        // OBP0 maps color 3 -> black, color 1 -> light grey; distinct shades tell them apart
        val frame = render(PpuState(lcdc = 0x93, bgp = 0xE4, obp0 = 0xE4, ly = 0, dot = 0), v, o)
        frame[0] shouldBe 0xAAAAAA // near sprite (x=1, color 1) wins pixel 0
        frame[1] shouldBe 0x000000 // sprite 0 (color 3) owns 1..7 alone
    }

    test("ppu state uses identity equality") {
        val a = PpuState()
        val b = PpuState()
        (a == a) shouldBe true
        (a == b) shouldBe false
        a.hashCode() shouldBe a.hashCode()
    }

    test("a disabled LCD does not advance and LY stays 0") {
        var p = PpuState(lcdc = 0x11, ly = 0, dot = 0) // bit 7 clear
        val (next, vbl, stat) = p.advance(456 * 200)
        next.ly shouldBe 0
        vbl shouldBe false
        stat shouldBe false
    }

    test("turning the LCD off resets LY/dot/mode but retains the LYC latch") {
        var p = PpuState(ly = 77, dot = 123, lyc = 77, mode = PpuMode.Transfer)
        p = p.advance(4).first // latch the LY==LYC comparison
        p.lycFlag shouldBe true
        p = lcdcWrite(p, 0x11).ppu // bit 7 off
        p.ly shouldBe 0
        p.dot shouldBe 0
        p.mode shouldBe PpuMode.HBlank
        p.lycFlag shouldBe true // frozen, not recomputed against LY=0
        statRead(p) and 0x04 shouldBe 0x04
    }

    test("re-enabling the LCD restarts the comparison clock and can fire the LYC interrupt") {
        // LCD off with the latch false, LYC=0, LYC source enabled
        val off = PpuState(lcdc = 0x11, ly = 0, lyc = 0, statEnables = 0x40, lycFlag = false, statLine = false)
        val on = lcdcWrite(off, 0x91)
        on.ppu.lycFlag shouldBe true
        on.irq shouldBe true
        // but if the latch was already true (line high), no edge fires
        val offHigh = PpuState(lcdc = 0x11, ly = 0, lyc = 0, statEnables = 0x40, lycFlag = true, statLine = true)
        lcdcWrite(offHigh, 0x91).irq shouldBe false
    }
})
