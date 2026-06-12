package gbc.core.system

import gbc.fixtures.Accuracy
import gbc.fixtures.Headless
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.io.File

class BlarggCpuSpec : FunSpec({
    tags(Accuracy)

    val roms = File(System.getProperty("testroms.dir", "../testroms"), "blargg/cpu_instrs")

    for (rom in roms.resolve("individual").listFiles { f -> f.name.endsWith(".gb") }!!.sorted()) {
        test("cpu_instrs ${rom.name}") {
            Headless.runBlarggSerial(rom.readBytes()) shouldContain "Passed"
        }
    }

    test("cpu_instrs combined (MBC1)") {
        Headless.runBlarggSerial(roms.resolve("cpu_instrs.gb").readBytes()) shouldContain "Passed"
    }
})
