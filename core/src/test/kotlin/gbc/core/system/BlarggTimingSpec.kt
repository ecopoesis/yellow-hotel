package gbc.core.system

import gbc.fixtures.Accuracy
import gbc.fixtures.Headless
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.io.File

class BlarggTimingSpec : FunSpec({
    tags(Accuracy)

    val blargg = File(System.getProperty("testroms.dir", "../testroms"), "blargg")

    test("instr_timing") {
        Headless.runBlarggSerial(blargg.resolve("instr_timing/instr_timing.gb").readBytes())
            .shouldContain("Passed")
    }

    for (rom in blargg.resolve("mem_timing/individual").listFiles { f -> f.name.endsWith(".gb") }!!.sorted()) {
        test("mem_timing ${rom.name}") {
            Headless.runBlarggSerial(rom.readBytes()) shouldContain "Passed"
        }
    }

    test("mem_timing combined") {
        Headless.runBlarggSerial(blargg.resolve("mem_timing/mem_timing.gb").readBytes())
            .shouldContain("Passed")
    }

    for (rom in blargg.resolve("mem_timing-2/rom_singles").listFiles { f -> f.name.endsWith(".gb") }!!.sorted()) {
        test("mem_timing-2 ${rom.name}") {
            Headless.runBlarggMemory(rom.readBytes()) shouldContain "Passed"
        }
    }

    test("mem_timing-2 combined") {
        Headless.runBlarggMemory(blargg.resolve("mem_timing-2/mem_timing.gb").readBytes())
            .shouldContain("Passed")
    }

    // halt_bug.gb waits on the VBlank interrupt, so it gates M4 (PPU), not M3.
})
