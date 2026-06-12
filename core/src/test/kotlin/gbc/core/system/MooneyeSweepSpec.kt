package gbc.core.system

import gbc.fixtures.Accuracy
import gbc.fixtures.Headless
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Regression net over the whole Mooneye acceptance tree. The committed
 * manifest (testroms/mooneye-expected.txt) lists every test known to pass;
 * any manifest entry failing breaks the build, and the manifest only grows.
 * Newly passing tests are written to build/reports/mooneye-passing.txt so
 * they can be promoted into the manifest.
 *
 * Hardware-revision variants we do not model (DMG0/MGB/SGB/AGB) are skipped.
 */
class MooneyeSweepSpec : FunSpec({
    tags(Accuracy)

    val base = File(System.getProperty("testroms.dir", "../testroms"), "mooneye")
    val manifestFile = base.resolve("../mooneye-expected.txt")
    val excluded = Regex(".*-(dmg0|mgb|sgb2?|S|A)\\.gb$")
    val cgbMode = Regex(".*-(C|cgb)\\.gb$")

    test("mooneye acceptance sweep matches the expected manifest") {
        val roms = base.resolve("acceptance").walkTopDown()
            .filter { it.name.endsWith(".gb") && !excluded.matches(it.name) }
            .sortedBy { it.relativeTo(base).path }
            .toList()

        val results = roms.associate { rom ->
            val mode = if (cgbMode.matches(rom.name)) HwMode.Cgb else HwMode.Dmg
            val name = rom.relativeTo(base).path
            name to (Headless.runMooneye(rom.readBytes(), mode, maxEmulatedSeconds = 20) == "passed")
        }

        val passing = results.filterValues { it }.keys.sorted()
        File("build/reports").mkdirs()
        File("build/reports/mooneye-passing.txt").writeText(passing.joinToString("\n") + "\n")
        println("mooneye sweep: ${passing.size}/${results.size} passing")

        val manifest = if (manifestFile.exists()) {
            manifestFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        } else {
            emptyList()
        }
        val regressions = manifest.filter { results[it] != true }
        if (regressions.isNotEmpty()) {
            fail("mooneye regressions (in manifest but failing):\n" + regressions.joinToString("\n"))
        }
        val newlyPassing = passing.filter { it !in manifest }
        if (newlyPassing.isNotEmpty()) {
            println("newly passing (add to mooneye-expected.txt):\n" + newlyPassing.joinToString("\n"))
        }
    }
})
