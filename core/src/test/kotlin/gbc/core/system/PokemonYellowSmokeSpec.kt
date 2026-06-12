package gbc.core.system

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
 * Boots the real Pokemon Yellow ROM in DMG mode and runs ~10 seconds of the
 * intro. Verifies the PPU is producing real content and exports the last
 * frame to build/reports/ for eyeballing. Skips when the ROM is absent.
 */
class PokemonYellowSmokeSpec : FunSpec({
    tags(Accuracy)

    val romFile = File(System.getProperty("game.rom", "../Pokemon Yellow.gbc"))

    test("Pokemon Yellow intro renders frames in DMG mode").config(enabledIf = { romFile.exists() }) {
        var s = Headless.boot(romFile.readBytes(), HwMode.Dmg)
        repeat(600) { s = stepFrame(s) } // ~10 seconds: Game Freak logo / intro animation

        val colors = s.ppu.frame.toSet()
        colors.size shouldBeGreaterThan 1 // not a blank screen

        val digest = MessageDigest.getInstance("SHA-256").run {
            for (px in s.ppu.frame) {
                update((px shr 16).toByte())
                update((px shr 8).toByte())
                update(px.toByte())
            }
            digest().joinToString("") { "%02x".format(it) }
        }
        println("pokemon-yellow frame-600 sha256=$digest colors=${colors.size}")
        // Golden frame: Pikachu surfing in the intro (verified visually 2026-06-11)
        digest shouldBe "e7c58d529de1d848ebffc12da52d26e4518540d3d2572e7df999d0a937e164f7"

        val img = BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until SCREEN_H) {
            for (x in 0 until SCREEN_W) img.setRGB(x, y, s.ppu.frame[y * SCREEN_W + x])
        }
        val out = File("build/reports/pokemon-yellow-frame600.png")
        out.parentFile.mkdirs()
        ImageIO.write(img, "png", out)
    }
})
