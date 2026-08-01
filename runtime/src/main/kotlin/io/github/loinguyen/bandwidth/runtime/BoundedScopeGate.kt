package io.github.loinguyen.bandwidth.runtime

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Runtime support for the future `@BoundedScope` IR rewrite.
 */
public class BoundedScopeGate(
    public val permits: Int,
) {
    init {
        require(permits > 0) { "permits must be positive" }
    }

    private val semaphore: Semaphore = Semaphore(permits)

    /**
     * Runs [block] after acquiring one permit and releases it when the block
     * completes, fails, or is cancelled.
     *
     * @param block the suspending operation protected by this gate.
     * @return the value returned by [block].
     */
    public suspend fun <T> withPermit(block: suspend () -> T): T =
        semaphore.withPermit { block() }
}
