package app.aaps.plugins.constraints.objectives

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.Objectives
import app.aaps.core.interfaces.constraints.Objectives.Companion.AUTOSENS_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.AUTO_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.CLOSED_LOOP_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.EXAM_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.FIRST_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.LGS_OBJECTIVE
import app.aaps.core.interfaces.constraints.Objectives.Companion.SMB_OBJECTIVE
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.icons.IcPluginObjectives
import app.aaps.plugins.constraints.R
import app.aaps.plugins.constraints.objectives.compose.ObjectivesComposeContent
import app.aaps.plugins.constraints.objectives.keys.ObjectivesBooleanComposedKey
import app.aaps.plugins.constraints.objectives.keys.ObjectivesLongComposedKey
import app.aaps.plugins.constraints.objectives.objectives.Objective
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObjectivesPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    config: Config,
    val objectives: List<@JvmSuppressWildcards Objective>
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.CONSTRAINTS)
        .composeContent { ObjectivesComposeContent() }
        .icon(IcPluginObjectives)
        .pluginName(app.aaps.core.ui.R.string.objectives)
        .shortName(R.string.objectives_shortname)
        .enableByDefault(config.APS)
        .description(R.string.description_objectives),
    ownPreferences = listOf(ObjectivesBooleanComposedKey::class.java, ObjectivesLongComposedKey::class.java),
    aapsLogger, rh, preferences
), PluginConstraints, Objectives {

    override suspend fun onStart() {
        super.onStart()
        markEveryObjectiveAccomplished()
    }

    /**
     * Marks every objective as started and accomplished unless it already carries a timestamp.
     *
     * The objectives gate nothing in this build (see the constraints below), so leaving some of them
     * open only produced a progress badge that never went away and a setup wizard step that asked to
     * start objective 1. Writing the timestamps rather than faking [Objective.isAccomplished] keeps
     * the data model and the display consistent: the list shows a real date, `accomplishedCount`
     * reaches `size`, and nothing downstream needs a special case.
     *
     * The timestamp is a minute in the past because [Objective.isAccomplished] compares strictly
     * against the current time.
     */
    private fun markEveryObjectiveAccomplished() {
        for (objective in objectives) {
            if (objective.accomplishedOn != 0L) continue
            val accomplishedAt = objective.dateUtil.now() - T.mins(1).msecs()
            objective.startedOn = accomplishedAt
            objective.accomplishedOn = accomplishedAt
        }
    }

    fun reset() {
        for (objective in objectives) {
            objective.startedOn = 0
            objective.accomplishedOn = 0
        }
        preferences.put(BooleanNonKey.ObjectivesBgIsAvailableInNs, false)
        preferences.put(BooleanNonKey.ObjectivesPumpStatusIsAvailableInNS, false)
        preferences.put(IntNonKey.ObjectivesManualEnacts, 0)
        preferences.put(BooleanNonKey.ObjectivesProfileSwitchUsed, false)
        preferences.put(BooleanNonKey.ObjectivesDisconnectUsed, false)
        preferences.put(BooleanNonKey.ObjectivesReconnectUsed, false)
        preferences.put(BooleanNonKey.ObjectivesTempTargetUsed, false)
        preferences.put(BooleanNonKey.ObjectivesLoopUsed, false)
        preferences.put(BooleanNonKey.ObjectivesScaleUsed, false)
        // Keep the invariant: clearing the progress flags must not leave objectives open again.
        markEveryObjectiveAccomplished()
    }

    fun allPriorAccomplished(position: Int): Boolean {
        var accomplished = true
        for (i in 0 until position) {
            accomplished = accomplished && objectives[i].isAccomplished
        }
        return accomplished
    }

    /**
     * Constraints interface
     *
     * The objectives do not gate any feature in this build. They stay visible and keep tracking
     * progress, but none of them switches something off: this is a build for a user who has been
     * running a closed loop for years, where the learning path only blocks features after a fresh
     * install or a database reset.
     *
     * What these constraints normally guard is still guarded elsewhere - the limits of the Safety
     * plugin, the pump driver constraints and the loop's own checks are untouched.
     */
    override fun isLoopInvocationAllowed(value: Constraint<Boolean>): Constraint<Boolean> = value
    override fun isLgsForced(value: Constraint<Boolean>): Constraint<Boolean> = value
    override suspend fun isClosedLoopAllowed(value: Constraint<Boolean>): Constraint<Boolean> = value
    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> = value
    override suspend fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> = value
    override fun isAutomationEnabled(value: Constraint<Boolean>): Constraint<Boolean> = value
    override fun isConcentrationEnabled(value: Constraint<Boolean>): Constraint<Boolean> = value

    override val size: Int get() = objectives.size
    override val accomplishedCount: Int get() = objectives.count { it.isAccomplished }

    override fun isAccomplished(index: Int) = objectives[index].isAccomplished
    override fun isStarted(index: Int): Boolean = objectives[index].isStarted
}
