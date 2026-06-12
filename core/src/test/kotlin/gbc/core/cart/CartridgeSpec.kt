package gbc.core.cart

import arrow.core.None
import arrow.core.left
import gbc.fixtures.SyntheticRom
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.arrow.core.shouldBeSome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

class CartridgeSpec : FunSpec({

    test("a ROM-only cart maps straight through and ignores ROM-area writes") {
        val cart = parseCartridge(SyntheticRom.banked(romSizeCode = 0x00, cartType = 0x00)).shouldBeRight()
        cart.mbc shouldBe MbcState.NoMbc
        SyntheticRom.stampAt({ cartRead(cart, it) }, 0x0000) shouldBe 0
        SyntheticRom.stampAt({ cartRead(cart, it) }, 0x4000) shouldBe 1
        cartRead(cartWrite(cart, 0x2000, 5), 0x4000 + 0x150) shouldBe 1 // unchanged
        cartRead(cart, 0xA000) shouldBe 0xFF // no RAM
    }

    test("unsupported mapper types are rejected") {
        parseCartridge(SyntheticRom.build(cartType = 0x0F, romSizeCode = 0x00)) shouldBe
            RomError.UnsupportedMbc(0x0F).left()
    }

    test("all MBC5 variant type codes map to MBC5") {
        for (type in listOf(0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E)) {
            parseCartridge(SyntheticRom.banked(romSizeCode = 0x05, cartType = type))
                .shouldBeRight().mbc.shouldBeInstanceOf<MbcState.Mbc5>()
        }
    }

    test("all MBC1 variant type codes map to MBC1") {
        for (type in listOf(0x01, 0x02, 0x03)) {
            parseCartridge(SyntheticRom.banked(romSizeCode = 0x01, cartType = type))
                .shouldBeRight().mbc.shouldBeInstanceOf<MbcState.Mbc1>()
        }
    }

    test("battery presence follows the cart type") {
        parseCartridge(SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03))
            .shouldBeRight().hasBattery shouldBe true
        parseCartridge(SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1A, ramSizeCode = 0x03))
            .shouldBeRight().hasBattery shouldBe false
    }

    test("battery RAM round-trips through export and import") {
        var cart = parseCartridge(
            SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03),
        ).shouldBeRight()
        cart = cartWrite(cart, 0x0000, 0x0A)
        cart = cartWrite(cart, 0xA000, 0x42)
        val image = batteryRam(cart).shouldBeSome()
        image.size shouldBe 32 * 1024

        val fresh = parseCartridge(
            SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03),
        ).shouldBeRight()
        val restored = loadBatteryRam(fresh, image).shouldBeRight()
        cartRead(cartWrite(restored, 0x0000, 0x0A), 0xA000) shouldBe 0x42
    }

    test("carts without a battery export no save image") {
        val cart = parseCartridge(SyntheticRom.banked(romSizeCode = 0x01, cartType = 0x01)).shouldBeRight()
        batteryRam(cart) shouldBe None
    }

    test("importing a save image of the wrong size is rejected") {
        val cart = parseCartridge(
            SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03),
        ).shouldBeRight()
        loadBatteryRam(cart, ByteArray(123)) shouldBe
            SaveError.WrongSize(expected = 32 * 1024, actual = 123).left()
    }

    test("cartridge state uses identity equality: owned arrays are never structurally compared") {
        val bytes = SyntheticRom.banked(romSizeCode = 0x01, cartType = 0x01)
        val a = parseCartridge(bytes).shouldBeRight()
        val b = parseCartridge(bytes).shouldBeRight()
        (a == a) shouldBe true
        (a == b) shouldBe false
        a.hashCode() shouldBe a.hashCode()
    }

    test("golden: the real Pokemon Yellow ROM parses to its documented header").config(
        enabledIf = { File("../Pokemon Yellow.gbc").exists() },
    ) {
        val cart = parseCartridge(File("../Pokemon Yellow.gbc").readBytes()).shouldBeRight()
        cart.header.title shouldBe "POKEMON YELLOW"
        cart.header.cgbFlag shouldBe CgbFlag.CgbCompatible
        cart.header.cartType shouldBe 0x1B
        cart.header.romBytes shouldBe 1024 * 1024
        cart.header.ramBytes shouldBe 32 * 1024
        cart.header.headerChecksum shouldBe 0x97
        cart.mbc.shouldBeInstanceOf<MbcState.Mbc5>()
        cart.hasBattery shouldBe true
    }
})
