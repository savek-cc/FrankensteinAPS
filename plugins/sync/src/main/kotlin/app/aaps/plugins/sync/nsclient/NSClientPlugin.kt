package app.aaps.plugins.sync.nsclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.nsclient.NSAlarm
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventSWSyncStatus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.sync.DataSyncSelector
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.sync.Sync
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.extensions.toJson
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.nsShared.NSClientFragment
import app.aaps.plugins.sync.nsShared.events.EventNSClientStatus
import app.aaps.plugins.sync.nsShared.events.EventNSClientUpdateGuiData
import app.aaps.plugins.sync.nsShared.events.EventNSClientUpdateGuiStatus
import app.aaps.plugins.sync.nsclient.data.AlarmAck
import app.aaps.plugins.sync.nsclient.extensions.toJson
import app.aaps.plugins.sync.nsclient.services.NSClientService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NSClientPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    private val aapsSchedulers: AapsSchedulers,
    private val rxBus: RxBus,
    rh: ResourceHelper,
    private val context: Context,
    private val fabricPrivacy: FabricPrivacy,
    private val sp: SP,
    private val receiverDelegate: ReceiverDelegate,
    private val config: Config,
    private val dataSyncSelectorV1: DataSyncSelectorV1,
    private val dateUtil: DateUtil,
    private val profileUtil: ProfileUtil,
    private val nsSettingsStatus: NSSettingsStatus,
    private val decimalFormatter: DecimalFormatter
) : NsClient, Sync, PluginBase(
    PluginDescription()
        .mainType(PluginType.SYNC)
        .fragmentClass(NSClientFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_nightscout_syncs)
        .pluginName(R.string.ns_client)
        .shortName(R.string.ns_client_short_name)
        .preferencesId(R.xml.pref_ns_client)
        .description(R.string.description_ns_client),
    aapsLogger, rh
) {

    private val disposable = CompositeDisposable()
    override val listLog: MutableList<EventNSClientNewLog> = ArrayList()
    override val dataSyncSelector: DataSyncSelector get() = dataSyncSelectorV1
    override var status = ""
    var nsClientService: NSClientService? = null
    val isAllowed: Boolean
        get() = receiverDelegate.allowed
    val blockingReason: String
        get() = receiverDelegate.blockingReason

    override fun onStart() {
        context.bindService(Intent(context, NSClientService::class.java), mConnection, Context.BIND_AUTO_CREATE)
        super.onStart()
        receiverDelegate.grabReceiversState()
        disposable += rxBus
            .toObservable(EventNSClientStatus::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event ->
                           status = event.getStatus(context)
                           rxBus.send(EventNSClientUpdateGuiStatus())
                           // Pass to setup wizard
                           rxBus.send(EventSWSyncStatus(event.getStatus(context)))
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ if (nsClientService != null) context.unbindService(mConnection) }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNSClientNewLog::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ event: EventNSClientNewLog ->
                           addToLog(event)
                           aapsLogger.debug(LTag.NSCLIENT, event.action + " " + event.logText)
                       }, fabricPrivacy::logException)
    }

    override fun onStop() {
        if (nsClientService != null) context.unbindService(mConnection)
        disposable.clear()
        super.onStop()
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)
        if (config.NSCLIENT) {
            preferenceFragment.findPreference<PreferenceScreen>(rh.gs(R.string.ns_sync_options))?.isVisible = false

            preferenceFragment.findPreference<SwitchPreference>(rh.gs(app.aaps.core.utils.R.string.key_ns_create_announcements_from_errors))?.isVisible = false
            preferenceFragment.findPreference<SwitchPreference>(rh.gs(app.aaps.core.utils.R.string.key_ns_create_announcements_from_carbs_req))?.isVisible = false
        }
        preferenceFragment.findPreference<SwitchPreference>(rh.gs(R.string.key_ns_receive_tbr_eb))?.isVisible = config.isEngineeringMode()
    }

    override val hasWritePermission: Boolean get() = nsClientService?.hasWriteAuth ?: false
    override val connected: Boolean get() = nsClientService?.isConnected ?: false

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            aapsLogger.debug(LTag.NSCLIENT, "Service is disconnected")
            nsClientService = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            aapsLogger.debug(LTag.NSCLIENT, "Service is connected")
            val mLocalBinder = service as NSClientService.LocalBinder?
            nsClientService = mLocalBinder?.serviceInstance // is null when running in roboelectric
        }
    }

    override fun detectedNsVersion(): String = nsSettingsStatus.getVersion()

    private fun addToLog(ev: EventNSClientNewLog) {
        synchronized(listLog) {
            listLog.add(0, ev)
            // remove the first line if log is too large
            if (listLog.size >= Constants.MAX_LOG_LINES) {
                listLog.removeAt(listLog.size - 1)
            }
            rxBus.send(EventNSClientUpdateGuiData())
        }
    }

    override fun resend(reason: String) {
        nsClientService?.resend(reason)
    }

    override fun pause(newState: Boolean) {
        sp.putBoolean(R.string.key_ns_paused, newState)
        rxBus.send(EventPreferenceChange(rh.gs(R.string.key_ns_paused)))
    }

    override val address: String get() = nsClientService?.nsURL ?: ""

    override fun handleClearAlarm(originalAlarm: NSAlarm, silenceTimeInMilliseconds: Long) {
        if (!isEnabled()) return
        if (!sp.getBoolean(R.string.key_ns_upload, true)) {
            aapsLogger.debug(LTag.NSCLIENT, "Upload disabled. Message dropped")
            return
        }
        nsClientService?.sendAlarmAck(
            AlarmAck().also { ack ->
                ack.level = originalAlarm.level()
                ack.group = originalAlarm.group()
                ack.silenceTime = silenceTimeInMilliseconds
            })
    }

    override fun updateLatestBgReceivedIfNewer(latestReceived: Long) {
        nsClientService?.let { if (latestReceived > it.latestDateInReceivedData) it.latestDateInReceivedData = latestReceived }
    }

    override fun updateLatestTreatmentReceivedIfNewer(latestReceived: Long) {
        nsClientService?.let { if (latestReceived > it.latestDateInReceivedData) it.latestDateInReceivedData = latestReceived }
    }

    override fun resetToFullSync() {
        dataSyncSelector.resetToNextFullSync()
    }

    override suspend fun nsAdd(collection: String, dataPair: DataSyncSelector.DataPair, progress: String, profile: Profile?): Boolean {
        when (dataPair) {
            is DataSyncSelector.PairBolus                  -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairCarbs                  -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairBolusCalculatorResult  -> dataPair.value.toJson(true, dateUtil, profileUtil)
            is DataSyncSelector.PairTemporaryTarget        -> dataPair.value.toJson(true, dateUtil, profileUtil)
            is DataSyncSelector.PairFood                   -> dataPair.value.toJson(true)
            is DataSyncSelector.PairGlucoseValue           -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairTherapyEvent           -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairDeviceStatus           -> dataPair.value.toJson(dateUtil)
            is DataSyncSelector.PairTemporaryBasal         -> dataPair.value.toJson(true, profile, dateUtil)
            is DataSyncSelector.PairExtendedBolus          -> dataPair.value.toJson(true, profile, dateUtil)
            is DataSyncSelector.PairProfileSwitch          -> dataPair.value.toJson(true, dateUtil, decimalFormatter)
            is DataSyncSelector.PairEffectiveProfileSwitch -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairOfflineEvent           -> dataPair.value.toJson(true, dateUtil)
            is DataSyncSelector.PairProfileStore           -> dataPair.value
            else                                           -> null
        }?.let { data ->
            nsClientService?.dbAdd(collection, data, dataPair, progress)
        }
        return true
    }

    override suspend fun nsUpdate(collection: String, dataPair: DataSyncSelector.DataPair, progress: String, profile: Profile?): Boolean {
        val id = when (dataPair) {
            is DataSyncSelector.PairBolus                  -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairCarbs                  -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairBolusCalculatorResult  -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairTemporaryTarget        -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairFood                   -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairGlucoseValue           -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairTherapyEvent           -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairTemporaryBasal         -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairExtendedBolus          -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairProfileSwitch          -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairEffectiveProfileSwitch -> dataPair.value.ids.nightscoutId
            is DataSyncSelector.PairOfflineEvent           -> dataPair.value.ids.nightscoutId
            else                                           -> error("Unsupported data type")
        }
        when (dataPair) {
            is DataSyncSelector.PairBolus                  -> dataPair.value.toJson(false, dateUtil)
            is DataSyncSelector.PairCarbs                  -> dataPair.value.toJson(false, dateUtil)
            is DataSyncSelector.PairBolusCalculatorResult  -> dataPair.value.toJson(false, dateUtil, profileUtil)
            is DataSyncSelector.PairTemporaryTarget        -> dataPair.value.toJson(false, dateUtil, profileUtil)
            is DataSyncSelector.PairFood                   -> dataPair.value.toJson(false)
            is DataSyncSelector.PairGlucoseValue           -> dataPair.value.toJson(false, dateUtil)
            is DataSyncSelector.PairTherapyEvent           -> dataPair.value.toJson(false, dateUtil)
            is DataSyncSelector.PairTemporaryBasal         -> dataPair.value.toJson(false, profile, dateUtil)
            is DataSyncSelector.PairExtendedBolus          -> dataPair.value.toJson(false, profile, dateUtil)
            is DataSyncSelector.PairProfileSwitch          -> dataPair.value.toJson(false, dateUtil, decimalFormatter)
            is DataSyncSelector.PairEffectiveProfileSwitch -> dataPair.value.toJson(false, dateUtil)
            is DataSyncSelector.PairOfflineEvent           -> dataPair.value.toJson(false, dateUtil)
            else                                           -> null
        }?.let { data ->
            nsClientService?.dbUpdate(collection, id, data, dataPair, progress)
        }
        return true
    }
}