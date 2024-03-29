package app.aaps.plugins.sync.tizen

import app.aaps.core.data.iob.CobInfo
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TB
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.shared.tests.BundleMock
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito

internal class TizenPluginTest : TestBaseWithProfile() {

    @Mock lateinit var loop: Loop
    @Mock lateinit var receiverStatusStore: ReceiverStatusStore
    @Mock lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Mock lateinit var autosensDataStore: AutosensDataStore
    @Mock lateinit var processedDeviceStatusData: ProcessedDeviceStatusData

    private lateinit var sut: TizenPlugin

    @BeforeEach
    fun setUp() {
        sut = TizenPlugin(
            aapsLogger, rh, aapsSchedulers, context, dateUtil, fabricPrivacy, rxBus, iobCobCalculator, processedTbrEbData, profileFunction, preferences, processedDeviceStatusData,
            loop, activePlugin, receiverStatusStore, config, glucoseStatusProvider
        )
        Mockito.`when`(iobCobCalculator.ads).thenReturn(autosensDataStore)
        Mockito.`when`(autosensDataStore.lastBg()).thenReturn(InMemoryGlucoseValue(1000, 100.0, sourceSensor = SourceSensor.UNKNOWN))
        Mockito.`when`(profileFunction.getProfile()).thenReturn(validProfile)
        Mockito.`when`(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
        Mockito.`when`(profileFunction.getProfileName()).thenReturn("TestProfile")
        Mockito.`when`(iobCobCalculator.calculateIobFromBolus()).thenReturn(IobTotal(System.currentTimeMillis()))
        Mockito.`when`(iobCobCalculator.getCobInfo("broadcast")).thenReturn(CobInfo(1000, 100.0, 10.0))
        Mockito.`when`(iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended()).thenReturn(IobTotal(System.currentTimeMillis()))
        Mockito.`when`(processedTbrEbData.getTempBasalIncludingConvertedExtended(anyLong()))
            .thenReturn(TB(timestamp = 1000, duration = 60000, isAbsolute = true, rate = 1.0, type = TB.Type.NORMAL))
        Mockito.`when`(processedDeviceStatusData.uploaderStatus).thenReturn("100%")
        Mockito.`when`(loop.lastRun).thenReturn(Loop.LastRun().also {
            it.lastTBREnact = 1000
            it.tbrSetByPump = instantiator.providePumpEnactResult().success(true).enacted(true)
        }
        )
        Mockito.`when`(activePlugin.activePump).thenReturn(testPumpPlugin)
        Mockito.`when`(glucoseStatusProvider.glucoseStatusData).thenReturn(GlucoseStatus(100.0))
        Mockito.`when`(processedDeviceStatusData.openAPSData).thenReturn(ProcessedDeviceStatusData.OpenAPSData().also {
            it.clockSuggested = 1000L
            it.suggested = RT(runningDynamicIsf = false)
            it.clockEnacted = 1000L
            it.enacted = RT(runningDynamicIsf = false)
        })
    }

    @Test
    fun prepareDataTestAPS() {
        Mockito.`when`(config.APS).thenReturn(true)
        val event = EventOverviewBolusProgress.also {
            it.status = "Some status"
            it.percent = 100
        }
        val bundle = BundleMock.mock()
        sut.prepareData(event, bundle)
        assertThat(bundle.containsKey("progressPercent")).isTrue()
        assertThat(bundle.containsKey("progressStatus")).isTrue()
        assertThat(bundle.containsKey("glucoseMgdl")).isTrue()
        assertThat(bundle.containsKey("glucoseTimeStamp")).isTrue()
        assertThat(bundle.containsKey("units")).isTrue()
        assertThat(bundle.containsKey("slopeArrow")).isTrue()
        assertThat(bundle.containsKey("deltaMgdl")).isTrue()
        assertThat(bundle.containsKey("avgDeltaMgdl")).isTrue()
        assertThat(bundle.containsKey("high")).isTrue()
        assertThat(bundle.containsKey("low")).isTrue()
        assertThat(bundle.containsKey("bolusIob")).isTrue()
        assertThat(bundle.containsKey("basalIob")).isTrue()
        assertThat(bundle.containsKey("iob")).isTrue()
        assertThat(bundle.containsKey("cob")).isTrue()
        assertThat(bundle.containsKey("futureCarbs")).isTrue()
        assertThat(bundle.containsKey("phoneBattery")).isTrue()
        assertThat(bundle.containsKey("rigBattery")).isTrue()
        assertThat(bundle.containsKey("suggestedTimeStamp")).isTrue()
        assertThat(bundle.containsKey("suggested")).isTrue()
        assertThat(bundle.containsKey("enactedTimeStamp")).isTrue()
        assertThat(bundle.containsKey("enacted")).isTrue()
        assertThat(bundle.containsKey("basalTimeStamp")).isTrue()
        assertThat(bundle.containsKey("baseBasal")).isTrue()
        assertThat(bundle.containsKey("profile")).isTrue()
        assertThat(bundle.containsKey("tempBasalStart")).isTrue()
        assertThat(bundle.containsKey("tempBasalDurationInMinutes")).isTrue()
        assertThat(bundle.containsKey("tempBasalString")).isTrue()
        assertThat(bundle.containsKey("pumpTimeStamp")).isTrue()
        assertThat(bundle.containsKey("pumpBattery")).isTrue()
        assertThat(bundle.containsKey("pumpReservoir")).isTrue()
        assertThat(bundle.containsKey("pumpStatus")).isTrue()
    }

    @Test
    fun prepareDataTestAAPSClient() {
        Mockito.`when`(config.APS).thenReturn(false)
        val event = EventOverviewBolusProgress.also {
            it.status = "Some status"
            it.percent = 100
        }
        val bundle = BundleMock.mock()
        sut.prepareData(event, bundle)
        assertThat(bundle.containsKey("progressPercent")).isTrue()
        assertThat(bundle.containsKey("progressStatus")).isTrue()
        assertThat(bundle.containsKey("glucoseMgdl")).isTrue()
        assertThat(bundle.containsKey("glucoseTimeStamp")).isTrue()
        assertThat(bundle.containsKey("units")).isTrue()
        assertThat(bundle.containsKey("slopeArrow")).isTrue()
        assertThat(bundle.containsKey("deltaMgdl")).isTrue()
        assertThat(bundle.containsKey("avgDeltaMgdl")).isTrue()
        assertThat(bundle.containsKey("high")).isTrue()
        assertThat(bundle.containsKey("low")).isTrue()
        assertThat(bundle.containsKey("bolusIob")).isTrue()
        assertThat(bundle.containsKey("basalIob")).isTrue()
        assertThat(bundle.containsKey("iob")).isTrue()
        assertThat(bundle.containsKey("cob")).isTrue()
        assertThat(bundle.containsKey("futureCarbs")).isTrue()
        assertThat(bundle.containsKey("phoneBattery")).isTrue()
        assertThat(bundle.containsKey("rigBattery")).isTrue()
        assertThat(bundle.containsKey("suggestedTimeStamp")).isTrue()
        assertThat(bundle.containsKey("suggested")).isTrue()
        assertThat(bundle.containsKey("enactedTimeStamp")).isTrue()
        assertThat(bundle.containsKey("enacted")).isTrue()
        assertThat(bundle.containsKey("basalTimeStamp")).isTrue()
        assertThat(bundle.containsKey("baseBasal")).isTrue()
        assertThat(bundle.containsKey("profile")).isTrue()
        assertThat(bundle.containsKey("tempBasalStart")).isTrue()
        assertThat(bundle.containsKey("tempBasalDurationInMinutes")).isTrue()
        assertThat(bundle.containsKey("tempBasalString")).isTrue()
        assertThat(bundle.containsKey("pumpTimeStamp")).isTrue()
        assertThat(bundle.containsKey("pumpBattery")).isTrue()
        assertThat(bundle.containsKey("pumpReservoir")).isTrue()
        assertThat(bundle.containsKey("pumpStatus")).isTrue()
    }
}
