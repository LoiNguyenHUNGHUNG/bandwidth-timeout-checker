package io.github.loinguyen.bandwidth.core

import java.math.BigInteger

/** A discharged rate/concurrency obligation `(r, n)` from the paper. */
public data class EffectPair(
    public val requiredRateBytesPerSecond: Rational,
    public val concurrency: Int,
) {
    init {
        require(requiredRateBytesPerSecond >= Rational.ZERO) {
            "required rate must be non-negative"
        }
        require(concurrency >= 0) { "concurrency must be non-negative" }
    }

    internal fun dominates(other: EffectPair): Boolean =
        requiredRateBytesPerSecond >= other.requiredRateBytesPerSecond &&
            concurrency >= other.concurrency &&
            this != other
}

/**
 * One primitive download instance before final concurrency discharge.
 *
 * [concurrency] is known only after Kotlin syntax establishes it. [selfBound] is a
 * trusted bound for instances of this download kind. It is retained when an
 * enclosing unknown-repetition construct recalculates [concurrency].
 */
public data class DownloadEffect(
    public val requiredRateBytesPerSecond: Rational,
    public val concurrency: Int?,
    public val selfBound: Int? = null,
) {
    init {
        require(requiredRateBytesPerSecond >= Rational.ZERO) {
            "required rate must be non-negative"
        }
        require(concurrency == null || concurrency >= 0) {
            "concurrency must be non-negative"
        }
        require(selfBound == null || selfBound > 0) { "selfBound must be positive" }
    }

    internal fun discharged(): EffectPair? =
        concurrency?.let { EffectPair(requiredRateBytesPerSecond, it) }
}

/** A finite set of primitive download effects. */
public class NetworkEffect private constructor(
    private val downloads: Set<DownloadEffect>,
) {
    public val obligations: List<EffectPair>
        get() = normalize(downloads.mapNotNullTo(mutableSetOf()) { it.discharged() }).sortedWith(
            compareByDescending<EffectPair> { it.requiredRateBytesPerSecond }
                .thenByDescending { it.concurrency },
        )

    /** True when program syntax has not established `n`. */
    public val hasUnresolvedConcurrency: Boolean
        get() = downloads.any { it.discharged() == null }

    public val maxConcurrency: Int
        get() = obligations.maxOfOrNull(EffectPair::concurrency) ?: 0

    private val rawMaxConcurrency: Int
        get() = downloads.maxOfOrNull { it.concurrency ?: Int.MAX_VALUE } ?: 0

    private val hasUnknownConcurrency: Boolean
        get() = downloads.any { it.concurrency == null }

    /** Sequential join: `Norm(Phi1 union Phi2)`. */
    public fun then(other: NetworkEffect): NetworkEffect =
        NetworkEffect(downloads + other.downloads)

    /** Parallel composition from Table 2 of the paper. */
    public fun parallel(other: NetworkEffect): NetworkEffect {
        val leftShift: Int = other.rawMaxConcurrency
        val rightShift: Int = rawMaxConcurrency
        return NetworkEffect(
            downloads.mapTo(mutableSetOf()) { download ->
                download.concurrentWith(other, leftShift)
            } + other.downloads.mapTo(mutableSetOf()) { download ->
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
        return NetworkEffect(downloads.mapTo(mutableSetOf()) { it.copy(selfBound = selfBound) })
    }

    /** Marks a repetition boundary whose concurrency syntax cannot establish. */
    public fun withUnknownConcurrency(): NetworkEffect =
        NetworkEffect(downloads.mapTo(mutableSetOf()) { it.copy(concurrency = null) })

    /**
     * Summarizes an unknown number of overlapping copies of this body.
     *
     * Every retained download may reach its own [DownloadEffect.selfBound], so
     * each resulting global concurrency is their sum. Self bounds stay on the
     * returned downloads for an enclosing repetition boundary.
     */
    public fun withUnknownRepetition(): NetworkEffect {
        val concurrency = downloads.sumOf { it.selfBound ?: return withUnknownConcurrency() }
        return NetworkEffect(
            downloads.mapTo(mutableSetOf()) { it.copy(concurrency = concurrency) },
        )
    }

    /** `ReqBW(Phi) = max { r * n | (r, n) in Phi }`. */
    public fun requiredBandwidthBytesPerSecond(): Rational {
        require(!hasUnresolvedConcurrency) {
            "cannot compute required bandwidth with unresolved concurrency"
        }
        return obligations.maxOfOrNull { it.requiredRateBytesPerSecond * it.concurrency }
            ?: Rational.ZERO
    }

    /** True when [other] is a safe over-approximation of this effect. */
    public fun isCoveredBy(other: NetworkEffect): Boolean =
        !hasUnresolvedConcurrency && !other.hasUnresolvedConcurrency && obligations.all { pair ->
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
        public val EMPTY: NetworkEffect = NetworkEffect(emptySet())

        public fun of(pairs: Collection<EffectPair>): NetworkEffect =
            NetworkEffect(
                pairs.mapTo(mutableSetOf()) {
                    DownloadEffect(it.requiredRateBytesPerSecond, concurrency = it.concurrency)
                },
            )

        public fun download(maxBytes: Long, completeTimeoutMillis: Long): NetworkEffect {
            require(maxBytes >= 0) { "maximum transfer size must be non-negative" }
            require(completeTimeoutMillis > 0) { "complete-call timeout must be positive" }
            val rate = Rational.of(
                maxBytes.toBigInteger() * BigInteger.valueOf(1_000),
                completeTimeoutMillis.toBigInteger(),
            )
            return NetworkEffect(setOf(DownloadEffect(rate, concurrency = 1)))
        }

        public fun summary(rMaxBytesPerSecond: Long, nMax: Int): NetworkEffect {
            require(rMaxBytesPerSecond >= 0) { "rMax must be non-negative" }
            require(nMax >= 0) { "nMax must be non-negative" }
            return if (nMax == 0) EMPTY else of(listOf(EffectPair(Rational.of(rMaxBytesPerSecond), nMax)))
        }

        private fun normalize(pairs: Set<EffectPair>): Set<EffectPair> =
            pairs.filterNotTo(mutableSetOf()) { pair ->
                pairs.any { candidate -> candidate.dominates(pair) }
            }
    }

    private fun DownloadEffect.concurrentWith(
        other: NetworkEffect,
        concurrencyShift: Int,
    ): DownloadEffect =
        if (concurrency == null || other.hasUnknownConcurrency) {
            copy(concurrency = null)
        } else {
            copy(concurrency = concurrency + concurrencyShift)
        }
}
