package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.nssdk.localmodel.treatment.NSEffectiveProfileSwitch
import app.aaps.core.nssdk.mapper.convertToRemoteAndBack
import app.aaps.plugins.sync.extensions.contentEqualsTo
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito

@Suppress("SpellCheckingInspection")
internal class EffectiveProfileSwitchExtensionKtTest : TestBaseWithProfile() {

    @Mock lateinit var insulin: Insulin

    private var insulinConfiguration: ICfg = ICfg("Insulin", 360 * 60 * 1000, 60 * 60 * 1000)

    @BeforeEach
    fun mock() {
        Mockito.`when`(insulin.iCfg).thenReturn(insulinConfiguration)
        Mockito.`when`(activePlugin.activeInsulin).thenReturn(insulin)
    }

    @Test
    fun toEffectiveProfileSwitch() {
        val profileSwitch = EPS(
            timestamp = 10000,
            isValid = true,
            basalBlocks = validProfile.basalBlocks,
            isfBlocks = validProfile.isfBlocks,
            icBlocks = validProfile.icBlocks,
            targetBlocks = validProfile.targetBlocks,
            glucoseUnit = validProfile.units,
            originalProfileName = "SomeProfile",
            originalCustomizedName = "SomeProfile (150%, 1h)",
            originalTimeshift = 3600000,
            originalPercentage = 150,
            originalDuration = 3600000,
            originalEnd = 0,
            iCfg = activePlugin.activeInsulin.iCfg.also {
                it.insulinEndTime = (validProfile.dia * 3600 * 1000).toLong()
            },
            ids = IDs(
                nightscoutId = "nightscoutId",
                pumpId = 11000,
                pumpType = PumpType.DANA_I,
                pumpSerial = "bbbb"
            )
        )

        val profileSwitch2 = (profileSwitch.toNSEffectiveProfileSwitch(dateUtil).convertToRemoteAndBack() as NSEffectiveProfileSwitch).toEffectiveProfileSwitch(dateUtil)!!
        assertThat(profileSwitch.contentEqualsTo(profileSwitch2)).isTrue()
        assertThat(profileSwitch.ids.contentEqualsTo(profileSwitch2.ids)).isTrue()
    }
}
