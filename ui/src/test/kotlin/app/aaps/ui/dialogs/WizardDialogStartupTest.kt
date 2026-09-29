package app.aaps.ui.dialogs

import android.view.View
import android.widget.Button
import android.widget.TextView
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.core.objects.aps.ApsDecisionSnapshot
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.data.model.TT
import app.aaps.core.ui.elements.NumberPicker
import app.aaps.ui.databinding.DialogWizardBinding
import app.aaps.ui.databinding.OkcancelBinding
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class WizardDialogStartupTest {
    private lateinit var dialog: WizardDialog
    private val binding = mock<DialogWizardBinding>()
    private val total = mock<TextView>()
    private val reason = mock<TextView>()
    private val ok = mock<Button>()
    private val carbs = mock<NumberPicker>()
    private val correction = mock<NumberPicker>()

    @BeforeEach fun prepare() {
        // No fragment constructor, Android thread, pump, or database is started by these tests.
        dialog = mock(defaultAnswer = CALLS_REAL_METHODS)
        val okcancel = mock<OkcancelBinding>()
        field(okcancel, "ok", ok)
        field(binding, "okcancel", okcancel)
        field(binding, "total", total)
        field(binding, "totalReason", reason)
        field(binding, "carbsInput", carbs)
        field(binding, "correctionInput", correction)
        field(binding, "deliveryReview", mock<Button>())
        field(dialog, "_binding", binding)
        field(dialog, "wizard", mock<BolusWizard>())
        dialog.aimiMealAssist = mock()
        dialog.activePlugin = mock()
        whenever(dialog.activePlugin.activeProfileSource).thenReturn(mock())
    }

    @Test fun losingProfileInvalidatesOldDoseAndExplainsDisabledOkWithoutClearingInput() {
        field(dialog, "dialogInitialized", true)
        WizardDialog::class.java.getDeclaredMethod("calculateInsulin").apply { isAccessible = true }.invoke(dialog)

        verify(ok).setEnabled(false)
        verify(ok).setVisibility(View.VISIBLE)
        verify(reason).setText("Профили пока недоступны. Расчёт обновится после их загрузки.")
        assertThat(value(dialog, "wizard")).isNull()
        assertThat(value(dialog, "dialogInitialized")).isEqualTo(false)
        verifyNoInteractions(carbs, correction)
    }

    @Test fun loadingStateCannotLeaveAPreviousWizardAvailableForSubmission() {
        WizardDialog::class.java.getDeclaredMethod("showCalculationUnavailable", String::class.java)
            .apply { isAccessible = true }.invoke(dialog, "Loading test")
        verify(total).setText("Нет расчёта")
        verify(reason).setText("Loading test")
        verify(reason).setVisibility(View.VISIBLE)
        verify(ok).setEnabled(false)
        assertThat(value(dialog, "wizard")).isNull()
        verifyNoInteractions(carbs, correction)
    }

    @Test fun typingDuringInitializationKeepsTheReasonVisibleAndCannotSubmitOldDose() {
        field(dialog, "dialogInitialized", false)
        WizardDialog::class.java.getDeclaredMethod("calculateInsulin").apply { isAccessible = true }.invoke(dialog)
        verify(ok).setEnabled(false)
        verify(reason).setText("Данные профиля ещё не загружены. Введённые значения сохраняются; расчёт обновится автоматически.")
        assertThat(value(dialog, "wizard")).isNull()
        verifyNoInteractions(carbs, correction)
    }

    @Test fun apsCarbRequirementComesFromTheSameSnapshotAndDoesNotRecalculateTheChart() {
        dialog.config = mock()
        whenever(dialog.config.APS).thenReturn(true)
        dialog.overviewData = mock()
        val profile = mock<Profile>()
        val method = WizardDialog::class.java.getDeclaredMethod("requiredCarbsForWizard",
            Profile::class.java, TT::class.java, Boolean::class.javaPrimitiveType, ApsDecisionSnapshot::class.java)
            .apply { isAccessible = true }
        for (grams in listOf(3, 0)) {
            val snapshot = ApsDecisionSnapshot(1L, ApsDecisionSnapshot.State.CURRENT, 0.0, grams)
            assertThat(method.invoke(dialog, profile, null, false, snapshot)).isEqualTo(grams)
        }
        // A pending insulin receipt may coexist with a verified carbohydrate requirement.
        val pending = ApsDecisionSnapshot(2L, ApsDecisionSnapshot.State.REVIEW_DELIVERY, null, 3)
        assertThat(method.invoke(dialog, profile, null, true, pending)).isEqualTo(3)
        verifyNoInteractions(profile, dialog.overviewData)
    }

    @Test fun unknownApsCarbRequirementIsNotConvertedToZeroOrAnOldChartValue() {
        dialog.config = mock()
        whenever(dialog.config.APS).thenReturn(true)
        dialog.overviewData = mock()
        val profile = mock<Profile>()
        val method = WizardDialog::class.java.getDeclaredMethod("requiredCarbsForWizard",
            Profile::class.java, TT::class.java, Boolean::class.javaPrimitiveType, ApsDecisionSnapshot::class.java)
            .apply { isAccessible = true }
        for (state in listOf(ApsDecisionSnapshot.State.STALE, ApsDecisionSnapshot.State.UNAVAILABLE,
            ApsDecisionSnapshot.State.WAITING_FOR_TREATMENT)) {
            val snapshot = ApsDecisionSnapshot(1L, state, null, null)
            assertThat(method.invoke(dialog, profile, null, false, snapshot)).isNull()
        }
        assertThat(method.invoke(dialog, profile, null, false, null)).isNull()
        verifyNoInteractions(profile, dialog.overviewData)
    }

    private fun field(instance: Any, name: String, value: Any) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private fun value(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
}
