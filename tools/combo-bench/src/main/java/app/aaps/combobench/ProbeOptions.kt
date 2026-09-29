package app.aaps.combobench

internal data class ProbeOptions(val transport: ProbeTransport, val timeoutSeconds: Int = 8, val holdSeconds: Int = 0) {
    init {
        require(timeoutSeconds == 8 || timeoutSeconds == 20) { "Unsupported diagnostic deadline" }
        require(holdSeconds == 0 || holdSeconds == 5) { "Unsupported socket hold duration" }
        require(holdSeconds == 0 || (timeoutSeconds == 8 && transport.authenticated)) { "Hold requires the standard authenticated probe" }
    }

    val permissionBudgetMs: Long get() = (timeoutSeconds + holdSeconds + if (holdSeconds > 0) 2 else 0) * 1000L
}
