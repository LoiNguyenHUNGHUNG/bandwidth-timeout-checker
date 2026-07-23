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

    public suspend fun <T> withPermit(block: suspend () -> T): T =
        semaphore.withPermit { block() }
}
