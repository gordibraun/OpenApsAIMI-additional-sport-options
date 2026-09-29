package app.aaps.wear.interaction.actions

internal object WatchControlDestination {
    fun resolve(component: String?, requestedKind: String?): String = when (component) {
        "app.aaps.wear.interaction.actions.WatchCarbsShortcut" -> "CARBS"
        "app.aaps.wear.interaction.actions.WatchActivityShortcut" -> "ACTIVITY"
        else -> requestedKind?.takeIf { it in setOf("CARBS", "INSULIN", "ACTIVITY") } ?: "CARBS"
    }
}
