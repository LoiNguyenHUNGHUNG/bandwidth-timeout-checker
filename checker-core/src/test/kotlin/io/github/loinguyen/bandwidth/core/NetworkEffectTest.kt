package io.github.loinguyen.bandwidth.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkEffectTest {
    @Test
    fun `discharges one shared client bound after parallel composition`() {
        val pool = NetworkPool(id = "images", maxConcurrentRequests = 2)
        val download = NetworkEffect.download(
            maxBytes = 1_000,
            completeTimeoutMillis = 1_000,
        ).through(pool)

        val result = download.boundedReplication(maxConcurrentBodies = 3)

        assertEquals(listOf(EffectPair(Rational.of(1_000), 2)), result.obligations)
        assertEquals(Rational.of(2_000), result.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `adds independent client bounds in parallel`() {
        val images = NetworkPool(id = "images", maxConcurrentRequests = 2)
        val api = NetworkPool(id = "api", maxConcurrentRequests = 2)
        val imageDownload = NetworkEffect.download(1_000, 1_000)
            .through(images)
            .boundedReplication(maxConcurrentBodies = 3)
        val apiDownload = NetworkEffect.download(1_000, 1_000)
            .through(api)
            .boundedReplication(maxConcurrentBodies = 3)

        val result = imageDownload.parallel(apiDownload)

        assertEquals(listOf(EffectPair(Rational.of(1_000), 4)), result.obligations)
        assertEquals(Rational.of(4_000), result.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `does not discharge client bound while unbounded work overlaps`() {
        val images = NetworkPool(id = "images", maxConcurrentRequests = 2)
        val imageDownload = NetworkEffect.download(1_000, 1_000)
            .through(images)
            .boundedReplication(maxConcurrentBodies = 3)
        val unboundedDownload = NetworkEffect.download(1_000, 1_000)

        val result = imageDownload.parallel(unboundedDownload)

        assertEquals(listOf(EffectPair(Rational.of(1_000), 4)), result.obligations)
        assertEquals(Rational.of(4_000), result.requiredBandwidthBytesPerSecond())
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
