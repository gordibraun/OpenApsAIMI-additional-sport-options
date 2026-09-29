package app.aaps.combobench

/** Includes the final packet, which TransportLayer.stop() sends outside its normal throttle. */
internal class ControlSessionWriter(
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    private val active: () -> Unit,
    private val inspect: (List<Byte>) -> String,
    private val write: (List<Byte>) -> Unit,
    private val attempted: (String) -> Unit,
    private val completed: (String) -> Unit
) {
    private var previousCompletedAt: Long? = null

    @Synchronized fun send(bytes: List<Byte>) {
        previousCompletedAt?.let { previous ->
            while (true) {
                val remaining = 200L - (now() - previous)
                if (remaining <= 0L) break
                active()
                sleep(remaining)
            }
        }
        active()
        val command = inspect(bytes)
        attempted(command)
        write(bytes)
        previousCompletedAt = now()
        completed(command)
    }
}
