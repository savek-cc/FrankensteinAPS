package app.aaps.plugins.main.general.actions

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.actions.Actions
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.main.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionsPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    config: Config
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(ActionsFragment::class.qualifiedName)
        .enableByDefault(config.APS || config.PUMPCONTROL)
        .visibleByDefault(config.APS || config.PUMPCONTROL)
        .simpleModePosition(PluginDescription.Position.TAB)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_action)
        .pluginName(R.string.actions)
        .shortName(R.string.actions_shortname)
        .description(R.string.description_actions),
    aapsLogger, rh
), Actions
