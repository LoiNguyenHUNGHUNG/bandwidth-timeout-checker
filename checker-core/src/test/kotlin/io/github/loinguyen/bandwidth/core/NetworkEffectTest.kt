package io.github.loinguyen.bandwidth.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkEffectTest {
    @Test
    fun `keeps known concurrency separate from self bound`() {
        val download = NetworkEffect.download(
            maxBytes = 1_000,
            completeTimeoutMillis = 1_000,
        ).withSelfBound(2)

        val result = download.boundedReplication(maxConcurrentBodies = 3)

        assertEquals(listOf(EffectPair(Rational.of(1_000), 3)), result.obligations)
        assertEquals(Rational.of(3_000), result.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `uses self bound when repetition makes concurrency unknown`() {
        val result = NetworkEffect.download(1_000, 1_000)
            .withUnknownConcurrency()
            .withSelfBound(3)

        assertEquals(listOf(EffectPair(Rational.of(1_000), 3)), result.obligations)
        assertEquals(Rational.of(3_000), result.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `keeps unknown concurrency unresolved without a self bound`() {
        val result = NetworkEffect.download(1_000, 1_000)
            .withUnknownConcurrency()

        assertTrue(result.hasUnresolvedConcurrency)
    }

    @Test
    fun `sequential join preserves rate-concurrency correlation`() {
        val fastSingle: NetworkEffect = NetworkEffect.of(
            listOf(EffectPair(Rational.of(10), 1)),
        )
        val slowTriple: NetworkEffect = NetworkEffect.of(
            listOf(EffectPair(Rational.of(1), 3)),
        )

        val result: NetworkEffect = fastSingle.then(slowTriple)

        assertEquals(Rational.of(10), result.requiredBandwidthBytesPerSecond())
        assertEquals(2, result.obligations.size)
    }

    @Test
    fun `parallel composition shifts each child by other maximum concurrency`() {
        val left: NetworkEffect = NetworkEffect.of(
            listOf(
                EffectPair(Rational.of(10), 1),
                EffectPair(Rational.of(6), 5),
            ),
        )
        val right: NetworkEffect = NetworkEffect.of(
            listOf(EffectPair(Rational.of(1), 10)),
        )

        val result: NetworkEffect = left.parallel(right)

        assertEquals(
            listOf(
                EffectPair(Rational.of(10), 11),
                EffectPair(Rational.of(6), 15),
            ),
            result.obligations,
        )
        assertEquals(Rational.of(110), result.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `download uses exact bytes per second`() {
        val result: NetworkEffect = NetworkEffect.download(
            maxBytes = 1,
            completeTimeoutMillis = 3,
        )

        assertEquals(
            Rational.of(1_000, 3),
            result.obligations.single().requiredRateBytesPerSecond,
        )
    }

    @Test
    fun `bounded launches replicate the whole body effect`() {
        val body: NetworkEffect = NetworkEffect.download(
            maxBytes = 1_000,
            completeTimeoutMillis = 1_000,
        ).parallel(
            NetworkEffect.download(
                maxBytes = 500,
                completeTimeoutMillis = 1_000,
            ),
        )

        val result: NetworkEffect = body.boundedReplication(maxConcurrentBodies = 4)

        assertEquals(8, result.maxConcurrency)
        assertEquals(Rational.of(8_000), result.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `normalization removes dominated obligations`() {
        val result: NetworkEffect = NetworkEffect.of(
            listOf(
                EffectPair(Rational.of(1), 15),
                EffectPair(Rational.of(6), 15),
            ),
        )

        assertEquals(listOf(EffectPair(Rational.of(6), 15)), result.obligations)
        assertTrue(NetworkEffect.summary(1, 1).isCoveredBy(result))
    }
}
