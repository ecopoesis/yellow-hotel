package gbc.core.timer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Advance [n] M-cycles, returning the timer and whether any IRQ fired. */
private fun TimerState.advance(n: Int): Pair<TimerState, Boolean> {
    var t = this
    var irq = false
    repeat(n) {
        val r = timerTick(t)
        t = r.timer
        irq = irq || r.irq
    }
    return t to irq
}

class TimerSpec : FunSpec({

    test("DIV is the upper byte of the system counter and ticks every 64 M-cycles") {
        var t = TimerState(sysCounter = 0)
        timerDivRead(t) shouldBe 0
        t = t.advance(63).first
        timerDivRead(t) shouldBe 0
        t = t.advance(1).first
        timerDivRead(t) shouldBe 1
    }

    test("writing DIV resets the whole counter") {
        val t = TimerState(sysCounter = 0xABCD)
        timerDivRead(timerDivWrite(t).timer) shouldBe 0
    }

    test("TIMA at 262144 Hz (TAC=05) increments every 16 T-cycles") {
        var t = TimerState(sysCounter = 0, tac = 0x05)
        t = t.advance(3).first // 12 T-cycles
        t.tima shouldBe 0
        t = t.advance(1).first // 16 T-cycles
        t.tima shouldBe 1
        t = t.advance(4).first
        t.tima shouldBe 2
    }

    test("TIMA frequencies follow TAC: 4096/262144/65536/16384 Hz") {
        for ((tac, tCyclesPerTick) in listOf(0x04 to 1024, 0x05 to 16, 0x06 to 64, 0x07 to 256)) {
            var t = TimerState(sysCounter = 0, tac = tac)
            t = t.advance(tCyclesPerTick / 4).first
            t.tima shouldBe 1
        }
    }

    test("TIMA does not tick when TAC enable is off") {
        var t = TimerState(sysCounter = 0, tac = 0x01) // freq bits set, enable off
        t = t.advance(1024).first
        t.tima shouldBe 0
    }

    test("a DIV write that drops the selected bit ticks TIMA (rapid-toggle quirk)") {
        // TAC=05 selects bit 3; counter with bit 3 set -> write DIV -> falling edge
        val t = TimerState(sysCounter = 0b1000, tac = 0x05)
        val r = timerDivWrite(t)
        r.timer.tima shouldBe 1
    }

    test("disabling the timer while the selected bit is high ticks TIMA (TAC glitch)") {
        val t = TimerState(sysCounter = 0b1000, tac = 0x05)
        val r = timerTacWrite(t, 0x01) // enable cleared
        r.timer.tima shouldBe 1
    }

    test("TIMA overflow reads 0 for one M-cycle, then reloads from TMA and raises the interrupt") {
        var t = TimerState(sysCounter = 0, tac = 0x05, tima = 0xFF, tma = 0x42)
        val (afterOverflow, irq1) = t.advance(4) // 16 T-cycles -> increment -> overflow
        afterOverflow.tima shouldBe 0x00 // the famous 1-M-cycle zero window
        irq1 shouldBe false
        val (afterReload, irq2) = afterOverflow.advance(1)
        afterReload.tima shouldBe 0x42
        irq2 shouldBe true
    }

    test("writing TIMA during the zero window cancels the reload and the interrupt") {
        var t = TimerState(sysCounter = 0, tac = 0x05, tima = 0xFF, tma = 0x42)
        t = t.advance(4).first // overflow: zero window open
        t = timerTimaWrite(t, 0x77)
        val (after, irq) = t.advance(1)
        after.tima shouldBe 0x77
        irq shouldBe false
    }

    test("writing TIMA during the reload cycle is ignored: TMA wins") {
        var t = TimerState(sysCounter = 0, tac = 0x05, tima = 0xFF, tma = 0x42)
        t = t.advance(4).first
        val r = timerTick(t) // reload cycle: TIMA=TMA, irq
        r.irq shouldBe true
        val written = timerTimaWrite(r.timer, 0x77)
        written.tima shouldBe 0x42
    }

    test("writing TMA during the reload cycle propagates to TIMA") {
        var t = TimerState(sysCounter = 0, tac = 0x05, tima = 0xFF, tma = 0x42)
        t = t.advance(4).first
        val r = timerTick(t) // reload cycle
        val written = timerTmaWrite(r.timer, 0x99)
        written.tima shouldBe 0x99
        written.tma shouldBe 0x99
    }

    test("TMA writes outside the reload cycle do not touch TIMA") {
        val t = timerTmaWrite(TimerState(sysCounter = 0, tima = 0x10), 0x99)
        t.tima shouldBe 0x10
        t.tma shouldBe 0x99
    }

    test("TAC reads back with upper bits set") {
        timerTacRead(TimerState(tac = 0x05)) shouldBe 0xFD
    }
})
