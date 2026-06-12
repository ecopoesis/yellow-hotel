package gbc.core.system

import gbc.fixtures.Headless
import gbc.fixtures.Perf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import java.io.File

/**
 * Headless throughput gate: 60 emulated seconds of Pokemon Yellow in CGB mode
 * must run at >= 4x realtime locally (>= 2x on shared CI runners, via
 * -PperfMultiple=2). Leaves headroom over the 1x the desktop app needs.
 */
class PerfSpec : FunSpec({
    tags(Perf)

    val romFile = File(System.getProperty("game.rom", "../Pokemon Yellow.gbc"))
    val requiredMultiple = System.getProperty("perf.multiple", "4").toDouble()

    test("60 emulated seconds run at >= ${requiredMultiple}x realtime").config(
        enabledIf = { romFile.exists() },
    ) {
        // Warm up the JIT on a couple of emulated seconds first
        var s = Headless.boot(romFile.readBytes(), HwMode.Cgb)
        while (s.tCycles < 2 * Headless.CYCLES_PER_SECOND) s = stepFrame(s)

        val emulatedSeconds = 60L
        val start = System.nanoTime()
        val target = s.tCycles + emulatedSeconds * Headless.CYCLES_PER_SECOND
        while (s.tCycles < target) s = stepFrame(s)
        val wallSeconds = (System.nanoTime() - start) / 1e9

        val multiple = emulatedSeconds / wallSeconds
        println("perf: ${"%.1f".format(multiple)}x realtime (60 emulated seconds in ${"%.2f".format(wallSeconds)}s)")
        multiple shouldBeGreaterThan requiredMultiple
    }
})
