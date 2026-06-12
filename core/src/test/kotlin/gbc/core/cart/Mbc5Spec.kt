package gbc.core.cart

import gbc.fixtures.SyntheticRom
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll

/** MBC5 + 32 KiB RAM + battery, 1 MiB ROM (64 banks) — Pokemon Yellow's configuration. */
private fun yellowShapedCart(): CartridgeState =
    parseCartridge(
        SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x1B, ramSizeCode = 0x03, cgbFlag = 0x80),
    ).shouldBeRight()

private fun CartridgeState.visibleBank(region: Int): Int =
    SyntheticRom.stampAt({ addr -> cartRead(this, addr) }, region)

class Mbc5Spec : FunSpec({

    test("bank 0 is fixed at 0x0000-0x3FFF and bank 1 is the power-on default at 0x4000") {
        val cart = yellowShapedCart()
        cart.visibleBank(0x0000) shouldBe 0
        cart.visibleBank(0x4000) shouldBe 1
    }

    test("writing 0x2000 selects the low 8 bits of the ROM bank") {
        val cart = cartWrite(yellowShapedCart(), 0x2000, 5)
        cart.visibleBank(0x4000) shouldBe 5
        cart.visibleBank(0x0000) shouldBe 0
    }

    test("unlike MBC1, bank 0 can be mapped at 0x4000") {
        val cart = cartWrite(yellowShapedCart(), 0x2000, 0)
        cart.visibleBank(0x4000) shouldBe 0
    }

    test("writing 0x3000 supplies bit 8 of the ROM bank, masked to the cart's bank count") {
        // 64-bank cart: bank 256+2 masks to bank 2
        var cart = yellowShapedCart()
        cart = cartWrite(cart, 0x3000, 1)
        cart = cartWrite(cart, 0x2000, 2)
        cart.visibleBank(0x4000) shouldBe 2
    }

    test("9-bit banking reaches bank 256 on an 8 MiB cart") {
        val cart = parseCartridge(SyntheticRom.banked(romSizeCode = 0x08, cartType = 0x19)).shouldBeRight()
        val banked = cartWrite(cartWrite(cart, 0x3000, 1), 0x2000, 0)
        banked.visibleBank(0x4000) shouldBe 256
    }

    test("cart RAM is disabled at power-on: reads are 0xFF and writes are ignored") {
        val cart = yellowShapedCart()
        cartRead(cart, 0xA000) shouldBe 0xFF
        val after = cartWrite(cart, 0xA000, 0x42)
        cartRead(cartWrite(after, 0x0000, 0x0A), 0xA000) shouldBe 0xFF
    }

    test("writing 0x0A to 0x0000 enables RAM for read/write round-trips") {
        var cart = cartWrite(yellowShapedCart(), 0x0000, 0x0A)
        cart = cartWrite(cart, 0xA123, 0x42)
        cartRead(cart, 0xA123) shouldBe 0x42
    }

    test("any non-0x0A value disables RAM again") {
        var cart = cartWrite(yellowShapedCart(), 0x0000, 0x0A)
        cart = cartWrite(cart, 0xA000, 0x42)
        cart = cartWrite(cart, 0x0000, 0x00)
        cartRead(cart, 0xA000) shouldBe 0xFF
    }

    test("0x4000 selects among the four 8 KiB RAM banks independently") {
        var cart = cartWrite(yellowShapedCart(), 0x0000, 0x0A)
        for (bank in 0..3) {
            cart = cartWrite(cart, 0x4000, bank)
            cart = cartWrite(cart, 0xA000, 0x10 + bank)
        }
        for (bank in 0..3) {
            cart = cartWrite(cart, 0x4000, bank)
            cartRead(cart, 0xA000) shouldBe 0x10 + bank
        }
    }

    test("RAM bank register is masked to available banks") {
        var cart = cartWrite(yellowShapedCart(), 0x0000, 0x0A)
        cart = cartWrite(cart, 0x4000, 0)
        cart = cartWrite(cart, 0xA000, 0x77)
        cart = cartWrite(cart, 0x4000, 4) // masks to bank 0 on a 4-bank cart
        cartRead(cart, 0xA000) shouldBe 0x77
    }

    test("a RAM-less MBC5 cart reads 0xFF even when enabled") {
        var cart = parseCartridge(SyntheticRom.banked(romSizeCode = 0x05, cartType = 0x19)).shouldBeRight()
        cart = cartWrite(cart, 0x0000, 0x0A)
        cartRead(cart, 0xA000) shouldBe 0xFF
        cartWrite(cart, 0xA000, 0x42) // must not throw
    }

    test("property: visible ROM bank always equals the model prediction") {
        checkAll(Arb.list(Arb.pair(Arb.int(0x2000..0x3FFF), Arb.int(0..255)), 0..32)) { writes ->
            var cart = yellowShapedCart()
            var lo = 1
            var hi = 0
            for ((addr, value) in writes) {
                cart = cartWrite(cart, addr, value)
                if (addr < 0x3000) lo = value else hi = value and 0x01
            }
            cart.visibleBank(0x4000) shouldBe (((hi shl 8) or lo) and 63)
            cart.visibleBank(0x0000) shouldBe 0
        }
    }
})
