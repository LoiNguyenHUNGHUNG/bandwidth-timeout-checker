package io.github.loinguyen.bandwidth.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetworkEffectTest {
    @Test
    fun `keeps known concurrency separate from self bound`() {
        val download = NetworkEffect.download(
            maxBytes = 1_000,
            completeTimeoutMillis = 1_000,
        ).withSelfBound(2)

        val result = download.boundedReplication(maxConcurrentBodies = 3)

        assertEquals(listOf(DownloadEffect(Rational.of(1_000), 3, selfBound = 2)), result.obligations)
        assertEquals(Rational.of(3_000), result.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `uses summed self bounds for unknown repetition`() {
        val result = NetworkEffect.download(1_000, 1_000)
            .withSelfBound(3)
            .withUnknownRepetition()

        assertEquals(listOf(DownloadEffect(Rational.of(1_000), 3, selfBound = 3)), result.obligations)
        assertEquals(Rational.of(3_000), result.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `retains self bounds for nested unknown repetition`() {
        val body = NetworkEffect.download(1_000, 1_000)
            .withSelfBound(2)
            .then(NetworkEffect.download(500, 1_000).withSelfBound(3))

        val result = body.withUnknownRepetition().withUnknownRepetition()

        assertEquals(listOf(DownloadEffect(Rational.of(1_000), 5, selfBound = 5)), result.obligations)
    }

    @Test
    fun `rejects unknown repetition without a self bound`() {
        assertFailsWith<IllegalArgumentException> {
            NetworkEffect.download(1_000, 1_000).withUnknownRepetition()
        }
    }

    @Test
    fun `sequential join preserves rate-concurrency correlation`() {
        val fastSingle: NetworkEffect = NetworkEffect.of(
            listOf(DownloadEffect(Rational.of(10), 1)),
        )
        val slowTriple: NetworkEffect = NetworkEffect.of(
            listOf(DownloadEffect(Rational.of(1), 3)),
        )

        val result: NetworkEffect = fastSingle.then(slowTriple)

        assertEquals(Rational.of(10), result.requiredBandwidthBytesPerSecond())
        assertEquals(2, result.obligations.size)
    }

    @Test
    fun `parallel composition shifts each child by other maximum concurrency`() {
        val left: NetworkEffect = NetworkEffect.of(
            listOf(
                DownloadEffect(Rational.of(10), 1),
                DownloadEffect(Rational.of(6), 5),
            ),
        )
        val right: NetworkEffect = NetworkEffect.of(
            listOf(DownloadEffect(Rational.of(1), 10)),
        )

        val result: NetworkEffect = left.parallel(right)

        assertEquals(
            listOf(
                DownloadEffect(Rational.of(10), 11),
                DownloadEffect(Rational.of(6), 15),
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
                DownloadEffect(Rational.of(1), 15),
                DownloadEffect(Rational.of(6), 15),
            ),
        )

        assertEquals(listOf(DownloadEffect(Rational.of(6), 15)), result.obligations)
        assertTrue(NetworkEffect.summary(1, 1).isCoveredBy(result))
    }

    @Test
    fun `normalization accumulates self bounds of dominated effects`() {
        val result = NetworkEffect.of(
            listOf(
                DownloadEffect(Rational.of(6), 2, selfBound = 3),
                DownloadEffect(Rational.of(1), 1, selfBound = 2),
            ),
        )

        assertEquals(
            listOf(DownloadEffect(Rational.of(6), 2, selfBound = 5)),
            result.obligations,
        )
    }
}
