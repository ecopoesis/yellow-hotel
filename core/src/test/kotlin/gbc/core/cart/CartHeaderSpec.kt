package gbc.core.cart

import arrow.core.left
import gbc.fixtures.SyntheticRom
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CartHeaderSpec : FunSpec({

    test("parses a valid synthetic header") {
        val rom = SyntheticRom.build(
            title = "TEST",
            cgbFlag = 0x80,
            cartType = 0x00,
            romSizeCode = 0x00,
            ramSizeCode = 0x00,
        )

        val header = parseHeader(rom).shouldBeRight()

        header.title shouldBe "TEST"
        header.cgbFlag shouldBe CgbFlag.CgbCompatible
        header.cartType shouldBe 0x00
        header.romBytes shouldBe 32 * 1024
        header.ramBytes shouldBe 0
    }

    test("rejects a ROM smaller than the header area") {
        val tiny = ByteArray(0x100)
        parseHeader(tiny) shouldBe RomError.FileTooSmall(0x100).left()
    }

    test("rejects a corrupted header checksum") {
        val rom = SyntheticRom.build()
        rom[SyntheticRom.HEADER_CHECKSUM_ADDR] = 0x00
        val expected = SyntheticRom.headerChecksum(rom)
        parseHeader(rom) shouldBe RomError.HeaderChecksumMismatch(expected = expected, actual = 0x00).left()
    }

    test("rejects a ROM whose declared size disagrees with the file size") {
        val rom = SyntheticRom.build(romSizeCode = 0x01, actualSize = 0x8000) // declares 64 KiB, is 32 KiB
        parseHeader(rom) shouldBe RomError.RomSizeMismatch(declared = 0x10000, actual = 0x8000).left()
    }

    test("rejects an out-of-range ROM size code") {
        parseHeader(SyntheticRom.build(romSizeCode = 0x52)) shouldBe RomError.InvalidRomSize(0x52).left()
    }

    test("rejects an unknown RAM size code") {
        parseHeader(SyntheticRom.build(ramSizeCode = 0x07)) shouldBe RomError.InvalidRamSize(0x07).left()
    }

    test("maps all valid RAM size codes") {
        listOf(0x00 to 0, 0x01 to 2048, 0x02 to 8192, 0x03 to 32768, 0x04 to 131072, 0x05 to 65536)
            .forEach { (code, bytes) ->
                parseHeader(SyntheticRom.build(ramSizeCode = code)).shouldBeRight().ramBytes shouldBe bytes
            }
    }

    test("classifies all CGB flag values") {
        parseHeader(SyntheticRom.build(cgbFlag = 0x00)).shouldBeRight().cgbFlag shouldBe CgbFlag.DmgOnly
        parseHeader(SyntheticRom.build(cgbFlag = 0x80)).shouldBeRight().cgbFlag shouldBe CgbFlag.CgbCompatible
        parseHeader(SyntheticRom.build(cgbFlag = 0xC0)).shouldBeRight().cgbFlag shouldBe CgbFlag.CgbOnly
    }
})
