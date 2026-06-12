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

    /** The checksum algorithm the boot ROM runs over 0x134..0x14C. */
    fun headerChecksum(rom: ByteArray): Int {
        var x = 0
        for (i in 0x134..0x14C) x = (x - rom[i].toInt() - 1) and 0xFF
        return x
    }
}
