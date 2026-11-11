package io.github.loinguyen.bandwidth.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnalysisModeTest {
    private fun download(rate: Long, mode: AnalysisMode): NetworkEffect =
        NetworkEffect.download(rate, 1_000, mode)

    @Test
    fun `simplified algebra collapses each composition rather than only reporting maxima`() {
        val detailed = download(100, AnalysisMode.DETAILED).then(
            download(1, AnalysisMode.DETAILED).boundedReplication(3),
        )
        val simplified = download(100, AnalysisMode.SIMPLIFIED).then(
            download(1, AnalysisMode.SIMPLIFIED).boundedReplication(3),
        )
        assertEquals(listOf(Rational.of(100), Rational.of(1)),
            detailed.obligations.map { it.requiredRateBytesPerSecond })
        assertEquals(listOf(1, 3), detailed.obligations.map { it.concurrency })
        assertEquals(listOf(DownloadEffect(Rational.of(100), 3)), simplified.obligations)
        assertEquals(Rational.of(100), detailed.requiredBandwidthBytesPerSecond())
        assertEquals(Rational.of(300), simplified.requiredBandwidthBytesPerSecond())
        assertEquals(Rational.of(700), detailed.boundedReplication(3).requiredBandwidthBytesPerSecond())
        assertEquals(Rational.of(900), simplified.boundedReplication(3).requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `diagnostic retains dominated placeholders through composition and materialization`() {
        val diagnostic = download(1, AnalysisMode.DIAGNOSTIC)
            .then(download(2, AnalysisMode.DIAGNOSTIC))
            .parallel(download(3, AnalysisMode.DIAGNOSTIC))
        assertEquals(listOf(Rational.of(3), Rational.of(2), Rational.of(1)),
            diagnostic.obligations.map { it.requiredRateBytesPerSecond })
        assertTrue(diagnostic.obligations.all { it.concurrency == 2 })
        val detailed = download(1, AnalysisMode.DETAILED)
            .then(download(2, AnalysisMode.DETAILED))
            .parallel(download(3, AnalysisMode.DETAILED))
        assertEquals(listOf(DownloadEffect(Rational.of(3), 2)), detailed.obligations)
        assertEquals(detailed.requiredBandwidthBytesPerSecond(), diagnostic.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `diagnostic exact duplicates combine bounds without collapsing distinct concurrency`() {
        val effect = NetworkEffect.of(
            listOf(
                DownloadEffect(Rational.of(1), 1, selfBound = 2),
                DownloadEffect(Rational.of(1), 1, selfBound = 3),
                DownloadEffect(Rational.of(1), 2, selfBound = 4),
            ),
            AnalysisMode.DIAGNOSTIC,
        )
        assertEquals(listOf(2, 1), effect.obligations.map { it.concurrency })
        assertEquals(listOf(4, 5), effect.obligations.map { it.selfBound })
    }

    @Test
    fun `simplification keeps completing and escaping lifetimes separate until overlap`() {
        val effect = NetworkEffect.summary(100, 1, analysisMode = AnalysisMode.SIMPLIFIED)
            .then(NetworkEffect.summary(1, 3, DownloadLifetime.MAY_OUTLIVE_CALL, AnalysisMode.SIMPLIFIED))
        assertEquals(listOf(DownloadEffect(Rational.of(100), 1)), effect.completingOnly().obligations)
        assertEquals(3, effect.escapingOnly().maxConcurrency)
        assertEquals(4, effect.maxConcurrency)
        assertEquals(Rational.of(400), effect.requiredBandwidthBytesPerSecond())
    }

    @Test
    fun `empty effects remain identities and modes cannot leak between independent algebras`() {
        val simplified = download(100, AnalysisMode.SIMPLIFIED)
        assertEquals(simplified, NetworkEffect.EMPTY.then(simplified))
        assertEquals(simplified, simplified.parallel(NetworkEffect.EMPTY))
        assertEquals(AnalysisMode.DETAILED, NetworkEffect.download(100, 1_000).analysisMode)
        assertFailsWith<IllegalArgumentException> { simplified.then(NetworkEffect.download(1, 1_000)) }
        assertEquals(AnalysisMode.DIAGNOSTIC, AnalysisMode.parse("diagnostic"))
        assertFailsWith<IllegalArgumentException> { AnalysisMode.parse("raw") }
    }
}
