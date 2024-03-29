package app.aaps.pump.danar.comm

import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import dagger.android.HasAndroidInjector

class MsgSetSingleBasalProfile(
    injector: HasAndroidInjector,
    values: Array<Double>
) : MessageBase(injector) {

    // index 0-3
    init {
        setCommand(0x3302)
        for (i in 0..23) {
            addParamInt((values[i] * 100).toInt())
        }
        aapsLogger.debug(LTag.PUMPCOMM, "New message")
    }

    override fun handleMessage(bytes: ByteArray) {
        val result = intFromBuff(bytes, 0, 1)
        if (result != 1) {
            failed = true
            aapsLogger.debug(LTag.PUMPCOMM, "Set basal profile result: $result ERROR!!!")
            uiInteraction.addNotification(Notification.PROFILE_SET_FAILED, rh.gs(app.aaps.pump.dana.R.string.profile_set_failed), Notification.URGENT)
        } else {
            failed = false
            aapsLogger.debug(LTag.PUMPCOMM, "Set basal profile result: $result")
            uiInteraction.addNotificationValidFor(Notification.PROFILE_SET_OK, rh.gs(app.aaps.core.ui.R.string.profile_set_ok), Notification.INFO, 60)
        }
    }
}