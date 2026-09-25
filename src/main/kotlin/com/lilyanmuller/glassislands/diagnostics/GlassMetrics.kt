package com.lilyanmuller.glassislands.diagnostics

import java.util.EnumMap

/**
 * Counts and times the plugin's own work on the EDT, so its cost can be checked from the debug report instead of
 * guessed. Two `System.nanoTime` calls per measured operation; EDT only.
 */
object GlassMetrics {
    enum class Operation(val label: String) {
        EVENT("AWT events inspected"),
        FULL_SCAN("full layout scans"),
        INTERACTIVE_PASS("hover/selection passes"),
        NATIVE_CALL("native updates"),
        CONTENT_REFRESH("content refreshes"),
        OPACITY_SWEEP("opacity sweeps"),
    }

    private class Counter {
        var count = 0L
        var nanos = 0L
        var maxNanos = 0L
    }

    private val counters = EnumMap<Operation, Counter>(Operation::class.java).apply {
        Operation.entries.forEach { put(it, Counter()) }
    }

    inline fun <T> measure(operation: Operation, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(operation, System.nanoTime() - start)
        }
    }

    /** Counts an operation too cheap to be worth timing. */
    fun tick(operation: Operation) = record(operation, 0)

    @PublishedApi
    internal fun record(operation: Operation, nanos: Long) {
        val counter = counters.getValue(operation)
        counter.count++
        counter.nanos += nanos
        if (nanos > counter.maxNanos) counter.maxNanos = nanos
    }

    fun countOf(operation: Operation): Long = counters.getValue(operation).count

    fun report(): String = Operation.entries.joinToString("\n") { operation ->
        val counter = counters.getValue(operation)
        if (operation == Operation.EVENT) {
            "  ${operation.label.padEnd(24)} ${counter.count}"
        } else {
            val average = if (counter.count == 0L) 0.0 else counter.nanos / 1e6 / counter.count
            "  ${operation.label.padEnd(24)} ${counter.count} (avg %.2f ms, max %.2f ms, total %.0f ms)"
                .format(average, counter.maxNanos / 1e6, counter.nanos / 1e6)
        }
    }

    fun reset() = counters.values.forEach {
        it.count = 0
        it.nanos = 0
        it.maxNanos = 0
    }
}
