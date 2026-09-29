package app.aaps.wear.interaction.actions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WatchControlDestinationTest {
    @Test fun carbsShortcutCannotBeRedirectedToInsulin() {
        assertEquals("CARBS", WatchControlDestination.resolve("app.aaps.wear.interaction.actions.WatchCarbsShortcut", "INSULIN"))
    }

    @Test fun activityShortcutOpensActivityWithoutExtras() {
        assertEquals("ACTIVITY", WatchControlDestination.resolve("app.aaps.wear.interaction.actions.WatchActivityShortcut", null))
    }

    @Test fun existingMenuDestinationsArePreserved() {
        for (kind in listOf("CARBS", "INSULIN", "ACTIVITY")) {
            assertEquals(kind, WatchControlDestination.resolve("app.aaps.wear.interaction.actions.WatchControlActivity", kind))
        }
    }

    @Test fun missingOrUnknownDestinationFallsBackToBlankCarbsForm() {
        assertEquals("CARBS", WatchControlDestination.resolve(null, null))
        assertEquals("CARBS", WatchControlDestination.resolve(null, "UNKNOWN"))
    }
}
