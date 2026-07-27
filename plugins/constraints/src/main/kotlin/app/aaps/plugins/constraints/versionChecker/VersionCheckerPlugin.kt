package app.aaps.plugins.constraints.versionChecker

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.versionChecker.VersionCheckerUtils
import app.aaps.core.keys.LongComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.constraints.R
import app.aaps.plugins.constraints.versionChecker.keys.VersionCheckerLongKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VersionCheckerPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    private val versionCheckerUtils: VersionCheckerUtils,
    private val config: Config,
    private val dateUtil: DateUtil
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.CONSTRAINTS)
        .alwaysEnabled(true)
        .showInList { false }
        .pluginName(R.string.version_checker),
    ownPreferences = listOf(VersionCheckerLongKey::class.java),
    aapsLogger, rh, preferences
), PluginConstraints {

    /**
     * The expiry date does not constrain dosing in this build.
     *
     * Upstream sets max IOB to 0 once the build is past its expiry date, which stops every
     * correction beyond basal. This build is compiled from source and updated when there is a
     * reason to, so an expiry date that quietly switches off dosing is a hazard here rather than a
     * safeguard. The version check itself still runs and still logs what it finds.
     */
    override suspend fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        versionCheckerUtils.triggerCheckVersion()
        return maxIob
    }
}
