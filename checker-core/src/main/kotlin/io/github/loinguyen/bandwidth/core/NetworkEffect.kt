package io.github.loinguyen.bandwidth.core

import java.math.BigInteger

/**
 * One primitive download effect `(r, n, selfBound?)`.
 *
 * [concurrency] is always established by the effect rules. [selfBound] is a
 * trusted bound for instances of this download kind. It is retained when an
 * enclosing unknown-repetition construct recalculates [concurrency].
 */
public data class DownloadEffect(
    public val requiredRateBytesPerSecond: Rational,
    public val concurrency: Int,
    public val selfBound: Int? = null,
) {
    init {
        require(requiredRateBytesPerSecond >= Rational.ZERO) {
            "required rate must be non-negative"
        }
        require(concurrency >= 0) {
            "concurrency must be non-negative"
        }
        require(selfBound == null || selfBound > 0) { "selfBound must be positive" }
    }

    internal fun dominates(other: DownloadEffect): Boolean {
        return requiredRateBytesPerSecond >= other.requiredRateBytesPerSecond &&
            concurrency >= other.concurrency &&
            (requiredRateBytesPerSecond > other.requiredRateBytesPerSecond ||
                concurrency > other.concurrency)
    }

    internal fun hasSameRateAndConcurrency(other: DownloadEffect): Boolean =
        requiredRateBytesPerSecond == other.requiredRateBytesPerSecond &&
            concurrency == other.concurrency

    internal fun mergeSelfBound(other: DownloadEffect): DownloadEffect =
        copy(
            selfBound = when {
                selfBound == null || other.selfBound == null -> null
                else -> selfBound + other.selfBound
            },
        )

    internal fun mergeEquivalentSelfBound(other: DownloadEffect): DownloadEffect =
        copy(
            selfBound = when {
                selfBound == null || other.selfBound == null -> null
                selfBound == other.selfBound -> selfBound
                else -> selfBound + other.selfBound
            },
        )
}

/** A normalized finite set of primitive download effects. */
public class NetworkEffect private constructor(
    private val downloads: List<DownloadEffect>,
) {
    /** Final downloads. */
    public val obligations: List<DownloadEffect>
        get() = downloads.sortedWith(
            compareByDescending<DownloadEffect> { it.requiredRateBytesPerSecond }
                .thenByDescending { it.concurrency },
        )

    /** True when this effect can cross an unknown repetition boundary. */
    public val hasSelfBoundForEveryDownload: Boolean
        get() = downloads.all { it.selfBound != null }

    public val maxConcurrency: Int
        get() = downloads.maxOfOrNull { it.concurrency } ?: 0

    /** Sequential join: `Norm(Phi1 union Phi2)`. */
    public fun then(other: NetworkEffect): NetworkEffect =
        of(downloads + other.downloads)

    /** Parallel composition from Table 2 of the paper. */
    public fun parallel(other: NetworkEffect): NetworkEffect {
        val leftShift: Int = other.maxConcurrency
        val rightShift: Int = maxConcurrency
        return of(
            downloads.map { download ->
                download.concurrentWith(other, leftShift)
            } + other.downloads.map { download ->
                download.concurrentWith(this, rightShift)
            },
        )
    }

    /** Models at most [maxConcurrentBodies] overlapping copies of one body. */
    public fun boundedReplication(maxConcurrentBodies: Int): NetworkEffect {
        require(maxConcurrentBodies >= 0) { "replication bound must be non-negative" }
        var result: NetworkEffect = EMPTY
        repeat(maxConcurrentBodies) { result = result.parallel(this) }
        return result
    }

    /** Attaches a trusted runtime bound to primitive requests in this effect. */
    public fun withSelfBound(selfBound: Int): NetworkEffect {
        require(selfBound > 0) { "selfBound must be positive" }
        return of(downloads.map { it.copy(selfBound = selfBound) })
    }

    /**
     * Summarizes an unknown number of overlapping copies of this body.
     *
     * Every retained download may reach its own [DownloadEffect.selfBound], so
     * each resulting global concurrency is their sum. Self bounds stay on the
     * returned downloads for an enclosing repetition boundary.
     */
    public fun withUnknownRepetition(): NetworkEffect {
        require(hasSelfBoundForEveryDownload) {
            "unknown repetition requires a self bound for every download"
        }
        val concurrency = downloads.sumOf { requireNotNull(it.selfBound) }
        return of(downloads.map { it.copy(concurrency = concurrency) })
    }

    /** `ReqBW(Phi) = max { r * n | (r, n) in Phi }`. */
    public fun requiredBandwidthBytesPerSecond(): Rational {
        return obligations.maxOfOrNull {
            it.requiredRateBytesPerSecond * it.concurrency
        }
            ?: Rational.ZERO
    }

    /** True when [other] is a safe over-approximation of this effect. */
    public fun isCoveredBy(other: NetworkEffect): Boolean =
        obligations.all { pair ->
            other.obligations.any {
                it.requiredRateBytesPerSecond >= pair.requiredRateBytesPerSecond &&
                    it.concurrency >= pair.concurrency
            }
        }

    public override fun equals(other: Any?): Boolean =
        other is NetworkEffect && downloads == other.downloads

    public override fun hashCode(): Int = downloads.hashCode()

    public override fun toString(): String = obligations.joinToString(prefix = "{", postfix = "}")

    public companion object {
        public val EMPTY: NetworkEffect = NetworkEffect(emptyList())

        public fun of(downloads: Collection<DownloadEffect>): NetworkEffect =
            NetworkEffect(normalize(downloads))

        public fun download(maxBytes: Long, completeTimeoutMillis: Long): NetworkEffect {
            require(maxBytes >= 0) { "maximum transfer size must be non-negative" }
            require(completeTimeoutMillis > 0) { "complete-call timeout must be positive" }
            val rate = Rational.of(
                maxBytes.toBigInteger() * BigInteger.valueOf(1_000),
                completeTimeoutMillis.toBigInteger(),
            )
            return of(listOf(DownloadEffect(rate, concurrency = 1)))
        }

        public fun summary(rMaxBytesPerSecond: Long, nMax: Int): NetworkEffect {
            require(rMaxBytesPerSecond >= 0) { "rMax must be non-negative" }
            require(nMax >= 0) { "nMax must be non-negative" }
            return if (nMax == 0) EMPTY else of(
                listOf(DownloadEffect(Rational.of(rMaxBytesPerSecond), nMax)),
            )
        }

        private fun normalize(downloads: Collection<DownloadEffect>): List<DownloadEffect> {
            val normalized = mutableListOf<DownloadEffect>()
            downloads.forEach { download ->
                var merged = download
                var index = 0
                while (index < normalized.size) {
                    val candidate = normalized[index]
                    when {
                        candidate.hasSameRateAndConcurrency(merged) -> {
                            normalized[index] = candidate.mergeEquivalentSelfBound(merged)
                            return@forEach
                        }
                        candidate.dominates(merged) -> {
                            normalized[index] = candidate.mergeSelfBound(merged)
                            return@forEach
                        }
                        merged.dominates(candidate) -> {
                            merged = merged.mergeSelfBound(candidate)
                            normalized.removeAt(index)
                        }
                        else -> index++
                    }
                }
                normalized += merged
            }
            return normalized
        }
    }

    private fun DownloadEffect.concurrentWith(
        other: NetworkEffect,
        concurrencyShift: Int,
    ): DownloadEffect = copy(concurrency = concurrency + concurrencyShift)
}
