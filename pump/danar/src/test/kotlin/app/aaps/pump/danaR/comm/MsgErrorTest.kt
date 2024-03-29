package app.aaps.pump.danaR.comm

import app.aaps.pump.danar.comm.MsgError
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class MsgErrorTest : DanaRTestBase() {

    @Test fun runTest() {
        val packet = MsgError(injector)

        // test message decoding
        val array = ByteArray(100)

        putByteToArray(array, 0, 1)
        packet.handleMessage(array)
        Assertions.assertEquals(true, packet.failed)
        // bigger than 8 - no error
        putByteToArray(array, 0, 10)
        packet.handleMessage(array)
        Assertions.assertEquals(false, packet.failed)
    }
}