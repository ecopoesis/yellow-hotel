package gbc.core.system

import gbc.fixtures.Accuracy
import gbc.fixtures.Headless
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.io.File

class BlarggSoundSpec : FunSpec({
    tags(Accuracy)

    val blargg = File(System.getProperty("testroms.dir", "../testroms"), "blargg")

    for (rom in blargg.resolve("dmg_sound/rom_singles").listFiles { f -> f.name.endsWith(".gb") }!!.sorted()) {
        test("dmg_sound ${rom.name}") {
            Headless.runBlarggMemory(rom.readBytes()) shouldContain "Passed"
        }
    }

    for (rom in blargg.resolve("cgb_sound/rom_singles").listFiles { f -> f.name.endsWith(".gb") }!!.sorted()) {
        test("cgb_sound ${rom.name}") {
            Headless.runBlarggMemory(rom.readBytes(), mode = HwMode.Cgb) shouldContain "Passed"
        }
    }
})
