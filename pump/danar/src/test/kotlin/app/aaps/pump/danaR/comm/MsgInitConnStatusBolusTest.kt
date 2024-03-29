package app.aaps.pump.danaR.comm

import app.aaps.pump.danar.comm.MsgInitConnStatusBolus
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class MsgInitConnStatusBolusTest : DanaRTestBase() {

    @Test fun runTest() {
        val packet = MsgInitConnStatusBolus(injector)

        // test message decoding
        var array = ByteArray(100)

        // message bigger than 22
        packet.handleMessage(array)
        Assertions.assertEquals(true, packet.failed)
        array = ByteArray(20)
        putByteToArray(array, 0, 1)
        packet.handleMessage(array)
        Assertions.assertEquals(true, danaPump.isExtendedBolusEnabled)
    }
}