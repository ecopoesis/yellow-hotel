package gbc.fixtures

/**
 * Builds minimal-but-valid ROM images for header/MBC tests, so unit tests
 * never depend on copyrighted game ROMs.
 */
object SyntheticRom {

    const val HEADER_CHECKSUM_ADDR = 0x14D

    fun build(
        title: String = "TEST",
        cgbFlag: Int = 0x00,
        cartType: Int = 0x00,
        romSizeCode: Int = 0x00,
        ramSizeCode: Int = 0x00,
        actualSize: Int = 0x8000,
    ): ByteArray {
        val rom = ByteArray(actualSize)
        title.toByteArray(Charsets.US_ASCII).copyInto(rom, 0x134, 0, minOf(title.length, 15))
        rom[0x143] = cgbFlag.toByte()
        rom[0x147] = cartType.toByte()
        rom[0x148] = romSizeCode.toByte()
        rom[0x149] = ramSizeCode.toByte()
        rom[HEADER_CHECKSUM_ADDR] = headerChecksum(rom).toByte()
        return rom
    }

    /**
     * A banked ROM where every 16 KiB bank is stamped with its own index at
     * bankStart+0x150 (low byte) and +0x151 (high byte), so tests can assert
     * exactly which bank an MBC mapped. Stamps sit outside the header checksum range.
     */
    fun banked(
        romSizeCode: Int,
        cartType: Int,
        ramSizeCode: Int = 0x00,
        cgbFlag: Int = 0x00,
    ): ByteArray {
        val size = 0x8000 shl romSizeCode
        val rom = build(
            title = "BANKTEST",
            cgbFlag = cgbFlag,
            cartType = cartType,
            romSizeCode = romSizeCode,
            ramSizeCode = ramSizeCode,
            actualSize = size,
        )
        for (bank in 0 until size / 0x4000) {
            rom[bank * 0x4000 + 0x150] = bank.toByte()
            rom[bank * 0x4000 + 0x151] = (bank shr 8).toByte()
        }
        return rom
    }

    /** Reads the bank stamp visible at [region] (0x0000 or 0x4000) through the given read function. */
    fun stampAt(read: (Int) -> Int, region: Int): Int =
        read(region + 0x150) or (read(region + 0x151) shl 8)

    /** The checksum algorithm the boot ROM runs over 0x134..0x14C. */
    fun headerChecksum(rom: ByteArray): Int {
        var x = 0
        for (i in 0x134..0x14C) x = (x - rom[i].toInt() - 1) and 0xFF
        return x
    }
}
