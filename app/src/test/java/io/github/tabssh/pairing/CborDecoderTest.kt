package io.github.tabssh.pairing

import org.junit.Assert.assertThrows
import org.junit.Test

class CborDecoderTest {

    @Test
    fun rejectsTrailingData() {
        assertThrows(Cbor.CborException::class.java) {
            Cbor.decode(byteArrayOf(0x01, 0x02))
        }
    }

    @Test
    fun rejectsLengthsLargerThanRemainingInput() {
        assertThrows(Cbor.CborException::class.java) {
            Cbor.decode(byteArrayOf(0x5A, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        }
    }
}
