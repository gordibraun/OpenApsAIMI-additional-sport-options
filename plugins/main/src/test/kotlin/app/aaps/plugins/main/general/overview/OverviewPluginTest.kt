package app.aaps.plugins.main.general.overview

import android.app.Activity
import android.widget.TextView
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.plugins.main.general.overview.notifications.NotificationStore
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import app.aaps.plugins.main.R

class OverviewPluginTest : TestBaseWithProfile() {

    @Mock lateinit var overviewData: OverviewData
    @Mock lateinit var overviewMenus: OverviewMenus
    @Mock lateinit var notificationStore: NotificationStore
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var nsSettingsStatus: NSSettingsStatus

    private lateinit var overviewPlugin: OverviewPlugin

    @BeforeEach fun prepare() {
        overviewPlugin = OverviewPlugin(
            aapsLogger, rh, preferences, notificationStore, fabricPrivacy, rxBus,
            aapsSchedulers, overviewData, overviewMenus, context, constraintsChecker, uiInteraction, nsSettingsStatus, config, activePlugin
        )
        whenever(uiInteraction.quickWizardListActivity).thenReturn(Activity::class.java)
    }

    @Test
    fun preferenceScreenTest() {
        val screen = preferenceManager.createPreferenceScreen(context)
        overviewPlugin.addPreferenceScreen(preferenceManager, screen, context, null)
        assertThat(screen.preferenceCount).isGreaterThan(0)
    }

    @Test fun localVersionUsesReleaseTitleAndCanBeOpened() {
        whenever(config.APS).thenReturn(true)
        whenever(rh.gs(R.string.overview_release_title)).thenReturn("1.2 - Food and basal")
        whenever(rh.gs(R.string.overview_release_open_changes)).thenReturn("%1\$s. Show changes")
        val view = mock<TextView>()
        overviewPlugin.setVersionView(view)
        verify(view).text = "1.2 - Food and basal"
        verify(view).isClickable = true
        verify(view).isFocusable = true
        verify(view).contentDescription = "1.2 - Food and basal. Show changes"
    }

    @Test fun clientDoesNotShowLocalAlgorithmReleaseDetails() {
        whenever(config.APS).thenReturn(false)
        whenever(config.PUMPCONTROL).thenReturn(false)
        val view = mock<TextView>()
        overviewPlugin.setVersionView(view)
        verify(view).text = ""
        verify(view).isClickable = false
        verify(view).contentDescription = null
    }
}
