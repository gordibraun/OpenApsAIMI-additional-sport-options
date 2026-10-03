package app.aaps.pump.combowatch

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.dialogs.OKDialog
import dagger.android.support.DaggerFragment
import javax.inject.Inject

/**
 * The one switch: Combo through the watch, or Combo directly from the phone.
 *
 * Switching is the Config Builder's own operation - the other pump driver is made the active one,
 * and AAPS is told a new pump is connected, exactly as when the choice is made there. What this
 * screen adds is a place to do it in one move and a plain statement of what has to follow: the
 * pump holds one pairing, so after the switch it is paired anew with the device that now drives it.
 */
class ComboModeFragment : DaggerFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var configBuilder: ConfigBuilder
    @Inject lateinit var pumpSync: PumpSync
    @Inject lateinit var watchDriver: ComboWatchPlugin

    private lateinit var state: TextView
    private lateinit var switch: SwitchCompat
    private lateinit var next: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val dp = context.resources.displayMetrics.density
        fun text(size: Float, bold: Boolean = false) = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            addView(text(18f, bold = true).apply { setText(R.string.combomode_title) })
            state = text(16f); addView(state)
            switch = SwitchCompat(context).apply {
                setText(R.string.combomode_switch)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
                // Only a tap by the owner asks; setting the position from the state does not.
                setOnClickListener { askToSwitch(isChecked) }
            }
            addView(switch)
            next = text(16f, bold = true).apply { visibility = View.GONE }; addView(next)
            addView(text(14f).apply { setText(R.string.combomode_explain) })
        }
        return ScrollView(context).apply { addView(column) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private val directDriver: PluginBase?
        get() = activePlugin.getSpecificPluginsListByInterface(Pump::class.java)
            .firstOrNull { it !== watchDriver && (it as? Pump)?.pumpDescription?.pumpType == PumpType.ACCU_CHEK_COMBO }

    private fun render() {
        val active = activePlugin.activePump
        val onWatch = active === watchDriver
        state.text = when {
            onWatch                 -> rh.gs(R.string.combomode_now_watch)
            active === directDriver -> rh.gs(R.string.combomode_now_direct)
            else                    -> rh.gs(R.string.combomode_now_other, (active as? PluginBase)?.name ?: "?")
        }
        switch.isChecked = onWatch
    }

    private fun askToSwitch(toWatch: Boolean) {
        val target: PluginBase? = if (toWatch) watchDriver else directDriver
        if (target == null) {
            next.setText(R.string.combomode_no_direct_driver); next.visibility = View.VISIBLE
            render()
            return
        }
        OKDialog.showConfirmation(
            requireActivity(), rh.gs(R.string.combomode_plugin_name),
            rh.gs(if (toWatch) R.string.combomode_confirm_to_watch else R.string.combomode_confirm_to_direct),
            {
                aapsLogger.info(LTag.PUMP, "combo mode: switching pump driver to ${target.name}")
                configBuilder.performPluginSwitch(target, true, PluginType.PUMP)
                // As the Config Builder does: a pump chosen anew keeps its records from now on.
                pumpSync.connectNewPump()
                next.setText(if (toWatch) R.string.combomode_next_watch else R.string.combomode_next_direct)
                next.visibility = View.VISIBLE
                render()
            },
            { render() }
        )
    }
}
