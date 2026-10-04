package app.aaps.pump.combowatch

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.ui.dialogs.OKDialog
import dagger.android.support.DaggerFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * The one switch: Combo through the watch, or Combo directly from the phone.
 *
 * Switching is the Config Builder's own operation - the other pump driver is made the active one,
 * and AAPS is told a new pump is connected, exactly as when the choice is made there. What this
 * screen adds is a place to do it in one move and a plain statement of what has to follow: the
 * pump holds one pairing, so after the switch it is paired anew with the device that now drives it.
 *
 * Below the switch, the link as the phone sees it: when the watch was last heard, what it said of
 * the pump, and the last lines of what passed between them - every command and its outcome, every
 * change the pump reported. The same things the direct driver's tab shows, for the watch.
 */
class ComboModeFragment : DaggerFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var configBuilder: ConfigBuilder
    @Inject lateinit var pumpSync: PumpSync
    @Inject lateinit var watchDriver: ComboWatchPlugin
    @Inject lateinit var link: ComboWatchLink
    @Inject lateinit var uiInteraction: UiInteraction

    private lateinit var state: TextView
    private lateinit var switch: SwitchCompat
    private lateinit var next: TextView
    private lateinit var linkState: TextView
    private lateinit var linkLog: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refreshLink = object : Runnable {
        override fun run() {
            renderLink()
            handler.postDelayed(this, LINK_REFRESH_MS)
        }
    }
    private val timeOfDay = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
            addView(text(18f, bold = true).apply { setText(R.string.combomode_link_title) })
            linkState = text(14f); addView(linkState)
            linkLog = text(12f).apply { typeface = android.graphics.Typeface.MONOSPACE }; addView(linkLog)
        }
        return ScrollView(context).apply { addView(column) }
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.post(refreshLink)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLink)
        super.onPause()
    }

    private fun renderLink() {
        val now = System.currentTimeMillis()
        val onWatch = activePlugin.activePump === watchDriver
        val lines = mutableListOf<String>()
        if (!onWatch) lines += rh.gs(R.string.combomode_link_inactive)
        else {
            val heard = link.lastContactEpochMs
            lines += if (heard == 0L) rh.gs(R.string.combomode_link_never) else rh.gs(R.string.combomode_link_heard, (now - heard) / 60_000L)
            link.lastHeartbeat?.let { heartbeat ->
                lines += rh.gs(
                    R.string.combomode_link_watch,
                    heartbeat.heldPump ?: "—",
                    rh.gs(if (heartbeat.pumpReachable) R.string.combomode_pump_answers else R.string.combomode_pump_silent),
                    heartbeat.watchBatteryPercent ?: 0
                )
            }
            link.lastSnapshot?.let { pump ->
                lines += rh.gs(
                    R.string.combomode_link_pump,
                    timeOfDay.format(Date(pump.readAtEpochMs)),
                    if (pump.tbrRunning && pump.tbrPercentage != null) "${pump.tbrPercentage} %" else rh.gs(R.string.combomode_profile_basal),
                    pump.reservoirUnits ?: 0
                )
            }
        }
        linkState.text = lines.joinToString("\n")
        val notes = link.recentNotes()
        linkLog.text =
            if (notes.isEmpty()) rh.gs(R.string.combomode_link_log_empty)
            else notes.asReversed().joinToString("\n") { "${timeOfDay.format(Date(it.atEpochMs))} ${it.text}" }
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
                // The steps that have to follow stay on the main screen until dismissed.
                uiInteraction.addNotification(
                    COMBO_MODE_REMINDER_NOTIFICATION,
                    rh.gs(if (toWatch) R.string.combomode_reminder_watch else R.string.combomode_reminder_direct),
                    Notification.NORMAL
                )
                next.setText(if (toWatch) R.string.combomode_next_watch else R.string.combomode_next_direct)
                next.visibility = View.VISIBLE
                render()
            },
            { render() }
        )
    }

    private companion object {

        const val LINK_REFRESH_MS = 5_000L

        /** The same id the Overview's switch uses, so the newer switch replaces the older reminder. */
        const val COMBO_MODE_REMINDER_NOTIFICATION = 9731
    }
}
