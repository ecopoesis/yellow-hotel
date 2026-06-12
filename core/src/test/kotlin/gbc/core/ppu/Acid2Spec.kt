package gbc.core.ppu

import gbc.fixtures.Accuracy
import gbc.fixtures.Headless
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import java.io.File
import javax.imageio.ImageIO

class Acid2Spec : FunSpec({
    tags(Accuracy)

    val acid2 = File(System.getProperty("testroms.dir", "../testroms"), "acid2")

    fun compare(name: String, frame: IntArray, referenceName: String) {
        val reference = ImageIO.read(acid2.resolve(referenceName))
        var mismatches = 0
        val examples = StringBuilder()
        for (y in 0 until SCREEN_H) {
            for (x in 0 until SCREEN_W) {
                val want = reference.getRGB(x, y) and 0xFFFFFF
                val got = frame[y * SCREEN_W + x] and 0xFFFFFF
                if (want != got) {
                    if (mismatches < 12) {
                        examples.append("  ($x,$y) want=%06X got=%06X\n".format(want, got))
                    }
                    mismatches++
                }
            }
        }
        if (mismatches > 0) {
            fail("$name: $mismatches/${SCREEN_W * SCREEN_H} pixels differ\n$examples")
        }
    }

    test("dmg-acid2 renders pixel-perfectly") {
        val s = Headless.runUntilBreakpoint(acid2.resolve("dmg-acid2.gb").readBytes())
        compare("dmg-acid2", s.ppu.frame, "dmg-acid2-reference.png")
    }

    test("cgb-acid2 renders pixel-perfectly") {
        // The v1.0 cgb-acid2 binary has no LD B,B breakpoint; it sets up for
        // ~14 frames and then renders the test image continuously. Run well
        // past setup and compare the steady-state frame.
        var s = Headless.boot(
            acid2.resolve("cgb-acid2.gbc").readBytes(),
            mode = gbc.core.system.HwMode.Cgb,
        )
        repeat(30) { s = gbc.core.system.stepFrame(s) }
        compare("cgb-acid2", s.ppu.frame, "cgb-acid2-reference.png")
    }
})
