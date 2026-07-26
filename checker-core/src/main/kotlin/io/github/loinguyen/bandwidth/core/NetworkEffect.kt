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
 * A runtime network scheduler. [id] preserves sharing while effects compose;
 * it is intentionally absent from the discharged public obligation.
 */
public data class NetworkPool(
    public val id: String,
    public val maxConcurrentRequests: Int,
) {
    init {
        require(id.isNotBlank()) { "network pool id must not be blank" }
        require(maxConcurrentRequests > 0) {
            "network pool limit must be positive"
        }
    }
}

private data class TrackedEffectPair(
    val pair: EffectPair,
    /** Null means syntax did not establish a finite concurrency bound. */
    val inferredConcurrency: Int? = pair.concurrency,
    val pools: Set<NetworkPool> = emptySet(),
) {
    fun discharged(): EffectPair? {
        val capacity = pools.sumOf(NetworkPool::maxConcurrentRequests)
        val concurrency = when (val inferred = inferredConcurrency) {
            null -> capacity.takeIf { it > 0 }
            else -> if (capacity == 0) inferred else minOf(inferred, capacity)
        }
        return concurrency?.let { pair.copy(concurrency = it) }
    }
}

/**
 * A finite set of raw rate/concurrency obligations. Public obligations are
 * Pareto-normalized after runtime network-pool capacities are discharged.
 */
public class NetworkEffect private constructor(
    private val trackedPairs: Set<TrackedEffectPair>,
) {
    public val obligations: List<EffectPair>
        get() = normalize(trackedPairs.mapNotNullTo(mutableSetOf()) { it.discharged() }).sortedWith(
            compareByDescending<EffectPair> { it.requiredRateBytesPerSecond }
                .thenByDescending { it.concurrency },
        )

    /** True when a final bandwidth check lacks both inferred n and nMax. */
    public val hasUnresolvedConcurrency: Boolean
        get() = trackedPairs.any { it.discharged() == null }

    public val maxConcurrency: Int
        get() = obligations.maxOfOrNull(EffectPair::concurrency) ?: 0

    private val rawMaxConcurrency: Int
        get() = trackedPairs.maxOfOrNull { it.inferredConcurrency ?: Int.MAX_VALUE } ?: 0

    private val hasUnknownRawConcurrency: Boolean
        get() = trackedPairs.any { it.inferredConcurrency == null }

    /**
     * Sequential join: `Norm(Phi1 union Phi2)`.
     */
    public fun then(other: NetworkEffect): NetworkEffect =
        NetworkEffect(trackedPairs + other.trackedPairs)

    /**
     * Parallel composition from Table 2 of the paper.
     */
    public fun parallel(other: NetworkEffect): NetworkEffect {
        val leftShift: Int = other.rawMaxConcurrency
        val rightShift: Int = rawMaxConcurrency
        val shiftedLeft = trackedPairs.mapTo(mutableSetOf()) { pair ->
            pair.concurrentWith(
                other = other,
                concurrencyShift = leftShift,
            )
        }
        val shiftedRight = other.trackedPairs.mapTo(mutableSetOf()) { pair ->
            pair.concurrentWith(
                other = this,
                concurrencyShift = rightShift,
            )
        }
        return NetworkEffect(shiftedLeft + shiftedRight)
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
     * Associates primitive requests in this effect with one runtime scheduler.
     * The scheduler bound is discharged only after sequential/parallel effect
     * composition has completed.
     */
    public fun through(pool: NetworkPool): NetworkEffect =
        NetworkEffect(
            trackedPairs.mapTo(mutableSetOf()) { pair ->
                pair.copy(pools = setOf(pool))
            },
        )

    /** Marks a boundary whose asynchronous concurrency cannot be inferred. */
    public fun withUnknownConcurrency(): NetworkEffect =
        NetworkEffect(
            trackedPairs.mapTo(mutableSetOf()) { pair ->
                pair.copy(inferredConcurrency = null)
            },
        )

    /**
     * `ReqBW(Phi) = max { r * n | (r, n) in Phi }`.
     */
    public fun requiredBandwidthBytesPerSecond(): Rational {
        require(!hasUnresolvedConcurrency) {
            "cannot compute required bandwidth with unresolved concurrency"
        }
        return obligations.maxOfOrNull { it.requiredRateBytesPerSecond * it.concurrency }
            ?: Rational.ZERO
    }

    /**
     * True when [other] is a safe over-approximation of this effect.
     */
    public fun isCoveredBy(other: NetworkEffect): Boolean =
        !hasUnresolvedConcurrency && !other.hasUnresolvedConcurrency && obligations.all { pair ->
            other.obligations.any {
                it.requiredRateBytesPerSecond >= pair.requiredRateBytesPerSecond &&
                    it.concurrency >= pair.concurrency
            }
        }

    public override fun equals(other: Any?): Boolean =
        other is NetworkEffect && trackedPairs == other.trackedPairs

    public override fun hashCode(): Int = trackedPairs.hashCode()

    public override fun toString(): String = obligations.joinToString(prefix = "{", postfix = "}")

    public companion object {
        public val EMPTY: NetworkEffect = NetworkEffect(emptySet())

        public fun of(pairs: Collection<EffectPair>): NetworkEffect =
            NetworkEffect(pairs.mapTo(mutableSetOf()) { TrackedEffectPair(it) })

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

    private fun TrackedEffectPair.concurrentWith(
        other: NetworkEffect,
        concurrencyShift: Int,
    ): TrackedEffectPair = copy(
        inferredConcurrency =
            if (inferredConcurrency == null || other.hasUnknownRawConcurrency) {
                null
            } else {
                inferredConcurrency + concurrencyShift
            },
        pools =
            if (pools.isNotEmpty() && other.hasOnlyBoundedRequests) {
                pools + other.concurrentPools
            } else {
                emptySet()
            },
    )

    private val concurrentPools: Set<NetworkPool>
        get() = trackedPairs.flatMapTo(mutableSetOf()) { it.pools }

    private val hasOnlyBoundedRequests: Boolean
        get() = trackedPairs.all { it.pools.isNotEmpty() }

}
