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

    test("dmg-acid2 renders pixel-perfectly") {
        val s = Headless.runUntilBreakpoint(acid2.resolve("dmg-acid2.gb").readBytes())
        val reference = ImageIO.read(acid2.resolve("dmg-acid2-reference.png"))

        var mismatches = 0
        val examples = StringBuilder()
        for (y in 0 until SCREEN_H) {
            for (x in 0 until SCREEN_W) {
                val want = reference.getRGB(x, y) and 0xFFFFFF
                val got = s.ppu.frame[y * SCREEN_W + x] and 0xFFFFFF
                if (want != got) {
                    if (mismatches < 12) {
                        examples.append("  ($x,$y) want=%06X got=%06X\n".format(want, got))
                    }
                    mismatches++
                }
            }
        }
        if (mismatches > 0) {
            fail("dmg-acid2: $mismatches/${SCREEN_W * SCREEN_H} pixels differ\n$examples")
        }
    }
})
