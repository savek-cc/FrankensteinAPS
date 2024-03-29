package app.aaps.plugins.sync.nsclient.extensions

import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.utils.JsonHelper

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.json.JSONObject

fun BCR.toJson(isAdd: Boolean, dateUtil: DateUtil, profileUtil: ProfileUtil): JSONObject =
    JSONObject()
        .put("eventType", TE.Type.BOLUS_WIZARD.text)
        .put("created_at", dateUtil.toISOString(timestamp))
        .put("isValid", isValid)
        .put("bolusCalculatorResult", Gson().toJson(this))
        .put("date", timestamp)
        .put("glucose", profileUtil.fromMgdlToUnits(glucoseValue))
        .put("units", profileUtil.units.asText)
        .put("notes", note)
        .also { if (isAdd && ids.nightscoutId != null) it.put("_id", ids.nightscoutId) }

fun BCR.Companion.fromJson(jsonObject: JSONObject): BCR? {
    val timestamp =
        JsonHelper.safeGetLongAllowNull(jsonObject, "mills", null)
            ?: JsonHelper.safeGetLongAllowNull(jsonObject, "date", null)
            ?: return null
    val isValid = JsonHelper.safeGetBoolean(jsonObject, "isValid", true)
    val id = JsonHelper.safeGetStringAllowNull(jsonObject, "identifier", null)
        ?: JsonHelper.safeGetStringAllowNull(jsonObject, "_id", null)
        ?: return null
    val bcrString = JsonHelper.safeGetStringAllowNull(jsonObject, "bolusCalculatorResult", null) ?: return null

    if (timestamp == 0L) return null

    return try {
        Gson().fromJson(bcrString, BCR::class.java)
            .also {
                it.id = 0
                it.isValid = isValid
                it.ids = IDs().apply { nightscoutId = id }
                it.version = 0
            }
    } catch (e: JsonSyntaxException) {
        null
    }
}
