package io.github.loinguyen.bandwidth.core

import java.math.BigInteger

/**
 * A rate/concurrency obligation `(r, n)` from the paper.
 *
 * [requiredRateBytesPerSecond] is the minimum service rate of one download and
 * [concurrency] is the maximum total number of downloads active with it.
 */
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
 * A finite, Pareto-normalized set of rate/concurrency obligations.
 */
public class NetworkEffect private constructor(
    pairs: Set<EffectPair>,
) {
    private val pairs: Set<EffectPair> = normalize(pairs)

    public val obligations: List<EffectPair>
        get() = pairs.sortedWith(
            compareByDescending<EffectPair> { it.requiredRateBytesPerSecond }
                .thenByDescending { it.concurrency },
        )

    public val maxConcurrency: Int
        get() = pairs.maxOfOrNull(EffectPair::concurrency) ?: 0

    /**
     * Sequential join: `Norm(Phi1 union Phi2)`.
     */
    public fun then(other: NetworkEffect): NetworkEffect = of(pairs + other.pairs)

    /**
     * Parallel composition from Table 2 of the paper.
     */
    public fun parallel(other: NetworkEffect): NetworkEffect {
        val leftShift: Int = other.maxConcurrency
        val rightShift: Int = maxConcurrency
        val shiftedLeft: Set<EffectPair> = pairs
            .mapTo(mutableSetOf()) { it.copy(concurrency = it.concurrency + leftShift) }
        val shiftedRight: Set<EffectPair> = other.pairs
            .mapTo(mutableSetOf()) { it.copy(concurrency = it.concurrency + rightShift) }
        return of(shiftedLeft + shiftedRight)
    }

    /**
     * Conservatively models at most [maxConcurrentBodies] copies of the same
     * launched body executing together.
     */
    public fun boundedReplication(maxConcurrentBodies: Int): NetworkEffect {
        require(maxConcurrentBodies >= 0) { "replication bound must be non-negative" }
        var result: NetworkEffect = EMPTY
        repeat(maxConcurrentBodies) {
            result = result.parallel(this)
        }
        return result
    }

    /**
     * `ReqBW(Phi) = max { r * n | (r, n) in Phi }`.
     */
    public fun requiredBandwidthBytesPerSecond(): Rational =
        pairs.maxOfOrNull { it.requiredRateBytesPerSecond * it.concurrency } ?: Rational.ZERO

    /**
     * True when [other] is a safe over-approximation of this effect.
     */
    public fun isCoveredBy(other: NetworkEffect): Boolean =
        pairs.all { pair ->
            other.pairs.any {
                it.requiredRateBytesPerSecond >= pair.requiredRateBytesPerSecond &&
                    it.concurrency >= pair.concurrency
            }
        }

    public override fun equals(other: Any?): Boolean =
        other is NetworkEffect && pairs == other.pairs

    public override fun hashCode(): Int = pairs.hashCode()

    public override fun toString(): String = obligations.joinToString(prefix = "{", postfix = "}")

    public companion object {
        public val EMPTY: NetworkEffect = NetworkEffect(emptySet())

        public fun of(pairs: Collection<EffectPair>): NetworkEffect =
            NetworkEffect(pairs.toSet())

        public fun download(maxBytes: Long, completeTimeoutMillis: Long): NetworkEffect {
            require(maxBytes >= 0) { "maximum transfer size must be non-negative" }
            require(completeTimeoutMillis > 0) { "complete-call timeout must be positive" }
            val rate: Rational = Rational.of(
                maxBytes.toBigInteger() * BigInteger.valueOf(1_000),
                completeTimeoutMillis.toBigInteger(),
            )
            return of(listOf(EffectPair(rate, 1)))
        }

        public fun summary(
            rMaxBytesPerSecond: Long,
            nMax: Int,
        ): NetworkEffect {
            require(rMaxBytesPerSecond >= 0) { "rMax must be non-negative" }
            require(nMax >= 0) { "nMax must be non-negative" }
            return if (nMax == 0) {
                EMPTY
            } else {
                of(listOf(EffectPair(Rational.of(rMaxBytesPerSecond), nMax)))
            }
        }

        private fun normalize(pairs: Set<EffectPair>): Set<EffectPair> =
            pairs.filterNotTo(mutableSetOf()) { pair ->
                pairs.any { candidate -> candidate.dominates(pair) }
            }
    }
}
