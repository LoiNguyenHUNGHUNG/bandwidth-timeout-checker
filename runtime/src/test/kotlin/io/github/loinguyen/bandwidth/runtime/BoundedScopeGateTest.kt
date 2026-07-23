package io.github.loinguyen.bandwidth.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BoundedScopeGateTest {
    @Test
    fun `gate enforces the declared maximum`() = runBlocking {
        val gate = BoundedScopeGate(permits = 4)
        val active = AtomicInteger()
        val peak = AtomicInteger()

        coroutineScope {
            List(20) {
                async {
                    gate.withPermit {
                        val now = active.incrementAndGet()
                        peak.updateAndGet { current -> maxOf(current, now) }
                        delay(5)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }

        assertEquals(4, peak.get())
    }
}
