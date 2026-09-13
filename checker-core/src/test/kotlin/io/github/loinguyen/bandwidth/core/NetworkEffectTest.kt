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

        assertEquals(listOf(DownloadEffect(Rational.of(1_000), 3, selfBound = 6)), result.obligations)
        assertEquals(Rational.of(3_000), result.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `shared gate preserves one invocation and bounds later repetition`() {
        val oneInvocation = NetworkEffect.download(1_000, 1_000)
            .parallel(NetworkEffect.download(1_000, 1_000))
            .withConcurrentInvocationBound(maxConcurrentInvocations = 4)

        assertEquals(
            listOf(DownloadEffect(Rational.of(1_000), 2, selfBound = 8)),
            oneInvocation.obligations,
        )

        val repeated = oneInvocation
            .withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL)
            .repeat()
        assertEquals(
            listOf(
                DownloadEffect(
                    Rational.of(1_000),
                    8,
                    selfBound = 8,
                    lifetime = DownloadLifetime.MAY_OUTLIVE_CALL,
                ),
            ),
            repeated.obligations,
        )
    }

    @Test
    fun `shared gate keeps a tighter existing download bound`() {
        val result = NetworkEffect.download(1_000, 1_000)
            .withSelfBound(2)
            .withConcurrentInvocationBound(maxConcurrentInvocations = 4)

        assertEquals(
            listOf(DownloadEffect(Rational.of(1_000), 1, selfBound = 2)),
            result.obligations,
        )
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
    fun `repeat keeps completing downloads sequential without bounds`() {
        val result = NetworkEffect.download(1_000, 1_000).repeat()

        assertEquals(
            listOf(DownloadEffect(Rational.of(1_000), 1)),
            result.obligations,
        )
    }

    @Test
    fun `repeat requires bounds only for escaping downloads`() {
        val effect = NetworkEffect.download(1_000, 1_000).then(
            NetworkEffect.download(500, 1_000)
                .withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL),
        )

        assertFailsWith<IllegalArgumentException> { effect.repeat() }
    }

    @Test
    fun `repeat overlaps bounded escaping downloads with completing work`() {
        val effect = NetworkEffect.download(1_000, 1_000).then(
            NetworkEffect.download(500, 1_000)
                .withSelfBound(3)
                .withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL),
        )

        val result = effect.repeat()

        assertEquals(
            listOf(
                DownloadEffect(Rational.of(1_000), 4),
                DownloadEffect(
                    Rational.of(500),
                    4,
                    selfBound = 3,
                    lifetime = DownloadLifetime.MAY_OUTLIVE_CALL,
                ),
            ),
            result.obligations,
        )
        assertEquals(Rational.of(4_000), result.requiredBandwidthBytesPerSecond())
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

    @Test
    fun `normalization accumulates equal bounds without assuming shared client identity`() {
        val result = NetworkEffect.of(
            listOf(
                DownloadEffect(Rational.of(1_000), 1, selfBound = 2),
                DownloadEffect(Rational.of(1_000), 1, selfBound = 2),
            ),
        ).withUnknownRepetition()

        assertEquals(
            listOf(DownloadEffect(Rational.of(1_000), 4, selfBound = 4)),
            result.obligations,
        )
    }

    @Test
    fun `sequential composition overlaps escaping work with later work`() {
        val escaping = NetworkEffect.download(1_000, 1_000)
            .withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL)
        val later = NetworkEffect.download(500, 1_000)

        val result = escaping.then(later)

        assertEquals(2, result.maxConcurrency)
        assertEquals(Rational.of(2_000), result.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `effect coverage preserves lifetime direction`() {
        val completing = NetworkEffect.summary(1_000, 2)
        val escaping = NetworkEffect.summary(
            1_000,
            2,
            lifetime = DownloadLifetime.MAY_OUTLIVE_CALL,
        )

        assertTrue(completing.isCoveredBy(escaping))
        assertTrue(!escaping.isCoveredBy(completing))
    }
}
