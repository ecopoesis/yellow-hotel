package gbc.app

import arrow.core.None
import gbc.fixtures.SyntheticRom
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class EmulatorSpec : FunSpec({

    test("battery save flushes to disk when the game disables cart RAM, and reloads on next start") {
        val dir = tempdir()
        val rom = SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03)
        listOf(
            0x3E, 0x0A, 0xEA, 0x00, 0x00, // LD A,0x0A; LD (0x0000),A: enable RAM
            0x3E, 0x42, 0xEA, 0x00, 0xA0, // LD A,0x42; LD (0xA000),A
            0x3E, 0x00, 0xEA, 0x00, 0x00, // disable RAM -> flush
            0x18, 0xFE, // spin
        ).forEachIndexed { i, b -> rom[0x100 + i] = b.toByte() }
        val romFile = dir.resolve("savegame.gbc")
        romFile.writeBytes(rom)

        Emulator(romFile, None).run(maxFrames = 10)

        val saveFile = dir.resolve("savegame.sav")
        saveFile.exists().shouldBeTrue()
        val image = saveFile.readBytes()
        image.size shouldBe 32 * 1024
        (image[0].toInt() and 0xFF) shouldBe 0x42

        // a second run must load the save without touching it (no RAM writes this time)
        val before = saveFile.lastModified()
        Emulator(romFile, None).run(maxFrames = 5)
        (saveFile.readBytes()[0].toInt() and 0xFF) shouldBe 0x42
        before shouldBe saveFile.lastModified() // unchanged content -> no rewrite
    }
})
