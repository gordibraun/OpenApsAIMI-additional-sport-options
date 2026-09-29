package app.aaps.combobench

internal object ManualReconnectPolicy {
    const val PUMP = "PUMP_10392647"
    const val ADDRESS = "00:0E:2F:E7:D1:95"

    fun validate(configuredPump: String?, pairedPump: String, address: String, stage: String,
                 active: Boolean, completed: Boolean, cleanupKnown: Boolean, stored: Boolean) {
        check(configuredPump == PUMP && pairedPump == PUMP && PairingTarget.matches(ADDRESS, address)) {
            "Проверка разрешена только для сопряжённой тестовой Combo 10392647"
        }
        check(stage == "PAIRED" && !active && completed && cleanupKnown && stored) {
            "Полное сопряжение Combo ещё не подтверждено"
        }
    }
}
