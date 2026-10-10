package dev.moduforge.script

/**
 * The clock scripts see: `mf.time`, `mf.sleep`, waits for interface events. Normally it is the wall
 * clock; `mfrg run --speed 60` makes it run faster so that a module that checks every five minutes can be
 * tried in seconds. A device never changes it.
 */
object ScriptClock {
    @Volatile
    private var scale = 1.0

    @Volatile
    private var originMillis = System.currentTimeMillis()

    /** Makes script time run [factor] times faster from now on. */
    fun speedUp(factor: Double) {
        require(factor >= 1.0 && factor <= MAX_FACTOR) { "speed must be between 1 and ${MAX_FACTOR.toInt()}" }
        originMillis = System.currentTimeMillis()
        scale = factor
    }

    /** Seconds since 1970 as the script is meant to see them. */
    fun nowSeconds(): Double {
        val real = System.currentTimeMillis()
        return (originMillis + (real - originMillis) * scale) / 1000.0
    }

    /** How many real milliseconds a wait of [seconds] script seconds takes. */
    fun realMillis(seconds: Double): Long = (seconds * 1000.0 / scale).toLong().coerceAtLeast(0)

    private const val MAX_FACTOR = 100_000.0
}
