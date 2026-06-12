package gbc.core.system

import gbc.fixtures.Accuracy
import gbc.fixtures.Headless
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Mooneye acceptance tests gating M3: timers, interrupts, OAM DMA, and the
 * timing tests satisfiable without a PPU. The full acceptance sweep is M9.
 */
private val M3_TESTS = listOf(
    "timer/div_write",
    "timer/tim00",
    "timer/tim00_div_trigger",
    "timer/tim01",
    "timer/tim01_div_trigger",
    "timer/tim10",
    "timer/tim10_div_trigger",
    "timer/tim11",
    "timer/tim11_div_trigger",
    "timer/tima_reload",
    "timer/tima_write_reloading",
    "timer/tma_write_reloading",
    "interrupts/ie_push",
    "oam_dma/basic",
    "oam_dma/reg_read",
    "oam_dma/sources-GS",
    "if_ie_registers",
    "rapid_di_ei",
    "ei_sequence",
    "ei_timing",
    "div_timing",
    "intr_timing",
    "reti_intr_timing",
    "halt_ime1_timing",
    // LY-polling tests, satisfiable since the M4 PPU:
    "oam_dma_restart",
    "oam_dma_start",
    "oam_dma_timing",
    "halt_ime0_ei",
    "halt_ime0_nointr_timing",
    // PPU STAT behavior (M4 gate):
    "ppu/stat_irq_blocking",
    "ppu/stat_lyc_onoff",
    "ppu/intr_1_2_timing-GS",
    "ppu/vblank_stat_intr-GS",
)

// Deferred to M9 hardening (documented straggler):
//   timer/rapid_toggle — TAC-toggle edge counting; fails on some real CGB revisions too

class MooneyeSpec : FunSpec({
    tags(Accuracy)

    val base = File(System.getProperty("testroms.dir", "../testroms"), "mooneye/acceptance")

    for (name in M3_TESTS) {
        test("mooneye $name") {
            Headless.runMooneye(base.resolve("$name.gb").readBytes()) shouldBe "passed"
        }
    }

    val emulatorOnly = File(System.getProperty("testroms.dir", "../testroms"), "mooneye/emulator-only")
    for (rom in emulatorOnly.resolve("mbc5").listFiles { f -> f.name.endsWith(".gb") }!!.sorted()) {
        test("mooneye mbc5/${rom.name}") {
            Headless.runMooneye(rom.readBytes()) shouldBe "passed"
        }
    }
})
