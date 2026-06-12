package gbc.core.system

import gbc.core.api.Button
import gbc.core.api.InputSource
import gbc.core.api.Ports
import gbc.core.ppu.SCREEN_H
import gbc.core.ppu.SCREEN_W
import gbc.fixtures.Accuracy
import gbc.fixtures.Headless
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * End-to-end gate on the real game (skipped when the ROM is absent): boots
 * Pokemon Yellow in DMG mode and walks the scripted path
 * copyright -> intro -> title screen -> main menu using Start presses.
 * Golden hashes pinned from visually verified frames exported to build/reports.
 */
class PokemonYellowSpec : FunSpec({
    tags(Accuracy)

    val romFile = File(System.getProperty("game.rom", "../Pokemon Yellow.gbc"))

    test("scripted input reaches the title screen and main menu").config(enabledIf = { romFile.exists() }) {
        var frame = 0
        val script = InputSource {
            when (frame) {
                in 200..205, in 450..455, in 700..705, in 950..955 -> Button.START
                else -> 0
            }
        }
        val ports = Ports(input = script)
        var s = Headless.boot(romFile.readBytes(), HwMode.Dmg)

        fun sha(): String = MessageDigest.getInstance("SHA-256").run {
            for (px in s.ppu.frame) {
                update((px shr 16).toByte())
                update((px shr 8).toByte())
                update(px.toByte())
            }
            digest().joinToString("") { "%02x".format(it) }
        }

        fun export(name: String) {
            val img = BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until SCREEN_H) for (x in 0 until SCREEN_W) img.setRGB(x, y, s.ppu.frame[y * SCREEN_W + x])
            File("build/reports").mkdirs()
            ImageIO.write(img, "png", File("build/reports/$name.png"))
        }

        while (frame < 900) {
            s = stepFrame(s, ports)
            frame++
        }
        export("pokemon-title")
        val titleSha = sha()

        while (frame < 1100) {
            s = stepFrame(s, ports)
            frame++
        }
        export("pokemon-menu")
        val menuSha = sha()

        // Pinned from visually verified frames (Pikachu title; NEW GAME/OPTION menu)
        titleSha shouldBe "4779c256701806059cb052687fa4a938efd624a8dad444847574a9aaa2c8c5c0"
        menuSha shouldBe "629ff172c7c3c7e3697f7bec3209af313102c2e36911ef038c32e737188d9b06"
    }

    test("Pokemon Yellow boots in color in CGB mode").config(enabledIf = { romFile.exists() }) {
        var frame = 0
        val script = InputSource {
            when (frame) {
                in 200..205, in 450..455, in 700..705 -> Button.START
                else -> 0
            }
        }
        val ports = Ports(input = script)
        var s = Headless.boot(romFile.readBytes(), HwMode.Cgb)
        s.mode shouldBe HwMode.Cgb
        while (frame < 900) {
            s = stepFrame(s, ports)
            frame++
        }
        val img = BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until SCREEN_H) for (x in 0 until SCREEN_W) img.setRGB(x, y, s.ppu.frame[y * SCREEN_W + x])
        File("build/reports").mkdirs()
        ImageIO.write(img, "png", File("build/reports/pokemon-cgb-title.png"))

        // Real color, not the 4 DMG greys
        val colors = s.ppu.frame.toSet()
        val nonGrey = colors.filter { c ->
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            !(r == g && g == b)
        }
        nonGrey.size shouldBeGreaterThan 2

        val sha = MessageDigest.getInstance("SHA-256").run {
            for (px in s.ppu.frame) {
                update((px shr 16).toByte())
                update((px shr 8).toByte())
                update(px.toByte())
            }
            digest().joinToString("") { "%02x".format(it) }
        }
        // Pinned from the visually verified color title screen (yellow Pikachu, red logo)
        sha shouldBe "bca6738830730c8ff23bb5fe9ffc54d4b279a79db9908099468035cfbdd329f0"
    }
})
