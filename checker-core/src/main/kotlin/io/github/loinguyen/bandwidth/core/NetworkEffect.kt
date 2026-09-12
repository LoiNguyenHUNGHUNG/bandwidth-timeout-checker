package io.github.loinguyen.bandwidth.core

import java.math.BigInteger

/** Whether a download finishes with the current call or may remain active afterward. */
public enum class DownloadLifetime {
    COMPLETES_WITH_CALL,
    MAY_OUTLIVE_CALL,
}

/**
 * One primitive download effect `(r, n, selfBound?, lifetime)`.
 *
 * [concurrency] is always established by the ordinary effect rules. [selfBound]
 * is a trusted bound for instances of this download kind; `null` denotes the
 * default bound infinity. At an unknown-repetition boundary, the effective
 * multiplicity is the minimum of the ordinary result and this bound. [lifetime]
 * records whether the download can still be active after this expression returns.
 */
public data class DownloadEffect(
    public val requiredRateBytesPerSecond: Rational,
    public val concurrency: Int,
    public val selfBound: Int? = null,
    public val lifetime: DownloadLifetime = DownloadLifetime.COMPLETES_WITH_CALL,
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

    /**
     * Returns whether this entry strictly dominates [other] within the same
     * lifetime component.
     */
    internal fun dominates(other: DownloadEffect): Boolean {
        if (lifetime != other.lifetime) return false
        return requiredRateBytesPerSecond >= other.requiredRateBytesPerSecond &&
            concurrency >= other.concurrency &&
            (requiredRateBytesPerSecond > other.requiredRateBytesPerSecond ||
                concurrency > other.concurrency)
    }

    /** Returns whether [other] has the same lifetime, rate, and concurrency. */
    internal fun hasSameRateAndConcurrency(other: DownloadEffect): Boolean =
        lifetime == other.lifetime &&
        requiredRateBytesPerSecond == other.requiredRateBytesPerSecond &&
            concurrency == other.concurrency

    /**
     * Merges the trusted self bounds of equivalent or dominating entries.
     *
     * A missing bound denotes infinity, so adding it to either entry makes the
     * merged bound infinite as well.
     */
    internal fun mergeSelfBound(other: DownloadEffect): DownloadEffect =
        copy(
            selfBound = when {
                selfBound == null || other.selfBound == null -> null
                else -> selfBound + other.selfBound
            },
        )

    /**
     * Returns whether this materialized obligation covers [other], allowing an
     * escaping lifetime to cover a completing lifetime.
     */
    internal fun materiallyCovers(other: DownloadEffect): Boolean =
        requiredRateBytesPerSecond >= other.requiredRateBytesPerSecond &&
            concurrency >= other.concurrency &&
            (lifetime == other.lifetime ||
                lifetime == DownloadLifetime.MAY_OUTLIVE_CALL)
}

/**
 * A normalized finite set of lifetime-tagged primitive download effects.
 *
 * Stored concurrency is local to one lifetime component. Public obligations
 * materialize completing and escaping work in parallel exactly once.
 */
public class NetworkEffect private constructor(
    private val downloads: List<DownloadEffect>,
) {
    /** Final bandwidth obligations after lifetime overlap is materialized. */
    public val obligations: List<DownloadEffect>
        get() = normalizeMaterialized(materializedDownloads()).sortedWith(
            compareByDescending<DownloadEffect> { it.requiredRateBytesPerSecond }
                .thenByDescending { it.concurrency },
        )

    /** True when this effect can cross an unknown repetition boundary. */
    public val hasSelfBoundForEveryDownload: Boolean
        get() = downloads.all { it.selfBound != null }

    /** True when every download that can escape one invocation has a self bound. */
    public val canRepeat: Boolean
        get() = escapingDownloads().all { it.selfBound != null }

    public val maxConcurrency: Int
        get() = obligations.maxOfOrNull { it.concurrency } ?: 0

    /**
     * Sequential composition. Completing work sequences normally, while work
     * that may outlive either operand remains mutually parallel.
     */
    public fun then(other: NetworkEffect): NetworkEffect =
        fromComponents(
            completing = normalize(completingDownloads() + other.completingDownloads()),
            escaping = parallelDownloads(escapingDownloads(), other.escapingDownloads()),
        )

    /** Parallel composition from Table 2, applied within each lifetime component. */
    public fun parallel(other: NetworkEffect): NetworkEffect {
        return fromComponents(
            completing = parallelDownloads(
                completingDownloads(),
                other.completingDownloads(),
            ),
            escaping = parallelDownloads(
                escapingDownloads(),
                other.escapingDownloads(),
            ),
        )
    }

    /** Alternative-path join: only one operand executes. */
    public fun choice(other: NetworkEffect): NetworkEffect =
        of(downloads + other.downloads)

    /** Models at most [maxConcurrentBodies] overlapping copies of one body. */
    public fun boundedReplication(maxConcurrentBodies: Int): NetworkEffect {
        require(maxConcurrentBodies >= 0) { "replication bound must be non-negative" }
        var result: NetworkEffect = EMPTY
        repeat(maxConcurrentBodies) { result = result.parallel(this) }
        return result
    }

    /**
     * Replaces the default infinite self bound with a trusted finite runtime
     * bound for primitive requests in this effect.
     */
    public fun withSelfBound(selfBound: Int): NetworkEffect {
        require(selfBound > 0) { "selfBound must be positive" }
        return of(downloads.map { it.copy(selfBound = selfBound) })
    }

    /**
     * Records that a shared gate admits at most [maxConcurrentInvocations]
     * active invocations of this whole effect without changing the effect of
     * one invocation. An entry exposing `n` downloads per invocation therefore
     * carries a repetition bound of `maxConcurrentInvocations * n`.
     *
     * An existing tighter bound, such as a client-wide limit, is preserved by
     * taking the minimum; a missing bound denotes infinity.
     * The bound becomes active only if an enclosing construct applies unknown
     * repetition. For an escaping repeated invocation, this realizes the peak
     * summary `repeat(k) { spawn { effect } }` without changing a single call.
     */
    public fun withConcurrentInvocationBound(maxConcurrentInvocations: Int): NetworkEffect {
        require(maxConcurrentInvocations > 0) {
            "concurrent invocation bound must be positive"
        }
        return of(
            downloads.map { download ->
                val gateBound = Math.multiplyExact(
                    maxConcurrentInvocations,
                    download.concurrency,
                )
                download.copy(
                    selfBound = download.selfBound?.coerceAtMost(gateBound) ?: gateBound,
                )
            },
        )
    }

    /**
     * Models an unknown number of serial invocations of this effect.
     *
     * Downloads that complete with one invocation remain sequential across
     * invocations. For escaping downloads, the ordinary repeated multiplicity
     * is infinity. The effective multiplicity is `min(infinity, selfBound)`, so
     * a finite self bound makes the result finite and a missing (infinite) bound
     * is rejected. An empty effect remains valid.
     */
    public fun repeat(): NetworkEffect {
        require(canRepeat) {
            "repeat requires a self bound for every escaping download"
        }
        val completing = completingOnly()
        val escaping = escapingOnly()
        if (escaping == EMPTY) return completing
        return completing.then(
            escaping.withUnknownRepetition(
                lifetime = DownloadLifetime.MAY_OUTLIVE_CALL,
            ),
        )
    }

    /**
     * Summarizes an unknown number of overlapping copies of this body.
     *
     * Every retained download may reach its own [DownloadEffect.selfBound], so
     * each resulting global concurrency is their sum. Self bounds stay on the
     * returned downloads for an enclosing repetition boundary.
     */
    public fun withUnknownRepetition(
        lifetime: DownloadLifetime = DownloadLifetime.COMPLETES_WITH_CALL,
    ): NetworkEffect {
        require(hasSelfBoundForEveryDownload) {
            "unknown repetition requires a self bound for every download"
        }
        val materialized = obligations
        val concurrency = materialized.sumOf { requireNotNull(it.selfBound) }
        return of(
            materialized.map {
                it.copy(concurrency = concurrency, lifetime = lifetime)
            },
        )
    }

    /** Materializes this whole effect at a new enclosing lifetime boundary. */
    public fun withLifetime(lifetime: DownloadLifetime): NetworkEffect =
        of(obligations.map { it.copy(lifetime = lifetime) })

    /** Projection used by structured-coroutine phase tracking. */
    public fun completingOnly(): NetworkEffect = of(completingDownloads())

    /** Projection used by structured-coroutine phase tracking. */
    public fun escapingOnly(): NetworkEffect = of(escapingDownloads())

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
                    it.concurrency >= pair.concurrency &&
                    it.lifetime.covers(pair.lifetime)
            }
        }

    /** Returns whether [other] contains the same normalized component entries. */
    public override fun equals(other: Any?): Boolean =
        other is NetworkEffect && downloads == other.downloads

    /** Returns a hash code consistent with [equals]. */
    public override fun hashCode(): Int = downloads.hashCode()

    /** Returns the materialized obligations in set notation. */
    public override fun toString(): String = obligations.joinToString(prefix = "{", postfix = "}")

    public companion object {
        public val EMPTY: NetworkEffect = NetworkEffect(emptyList())

        /**
         * Creates an effect from [downloads] and removes dominated entries.
         *
         * @return a normalized immutable effect.
         */
        public fun of(downloads: Collection<DownloadEffect>): NetworkEffect =
            NetworkEffect(normalize(downloads))

        /**
         * Creates the singleton effect of a transfer with a complete-call
         * timeout.
         *
         * @param maxBytes the maximum number of transferred bytes.
         * @param completeTimeoutMillis the positive end-to-end timeout in milliseconds.
         * @return an effect requiring `maxBytes * 1000 / completeTimeoutMillis`
         * bytes per second at concurrency one.
         * @throws IllegalArgumentException if [maxBytes] is negative or
         * [completeTimeoutMillis] is not positive.
         */
        public fun download(maxBytes: Long, completeTimeoutMillis: Long): NetworkEffect {
            require(maxBytes >= 0) { "maximum transfer size must be non-negative" }
            require(completeTimeoutMillis > 0) { "complete-call timeout must be positive" }
            val rate = Rational.of(
                maxBytes.toBigInteger() * BigInteger.valueOf(1_000),
                completeTimeoutMillis.toBigInteger(),
            )
            return of(listOf(DownloadEffect(rate, concurrency = 1)))
        }

        /**
         * Creates a single-entry effect from a declared rate and concurrency.
         *
         * @param rMaxBytesPerSecond the non-negative required rate.
         * @param nMax the non-negative maximum concurrency.
         * @param lifetime whether the summarized work completes with or may outlive its call.
         * @return [EMPTY] when [nMax] is zero; otherwise the declared effect.
         * @throws IllegalArgumentException if the rate or concurrency is negative.
         */
        public fun summary(
            rMaxBytesPerSecond: Long,
            nMax: Int,
            lifetime: DownloadLifetime = DownloadLifetime.COMPLETES_WITH_CALL,
        ): NetworkEffect {
            require(rMaxBytesPerSecond >= 0) { "rMax must be non-negative" }
            require(nMax >= 0) { "nMax must be non-negative" }
            return if (nMax == 0) EMPTY else of(
                listOf(DownloadEffect(Rational.of(rMaxBytesPerSecond), nMax, lifetime = lifetime)),
            )
        }

        /**
         * Pareto-normalizes [downloads] independently within each lifetime and
         * combines self bounds when entries collapse.
         */
        private fun normalize(downloads: Collection<DownloadEffect>): List<DownloadEffect> {
            val normalized = mutableListOf<DownloadEffect>()
            downloads.forEach { download ->
                var merged = download
                var index = 0
                while (index < normalized.size) {
                    val candidate = normalized[index]
                    when {
                        candidate.hasSameRateAndConcurrency(merged) -> {
                            normalized[index] = candidate.mergeSelfBound(merged)
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

        /** Creates an effect from already separated completing and escaping components. */
        private fun fromComponents(
            completing: Collection<DownloadEffect>,
            escaping: Collection<DownloadEffect>,
        ): NetworkEffect = NetworkEffect(normalize(completing + escaping))

        /**
         * Applies the parallel-composition concurrency shifts to [left] and
         * [right], then normalizes the result.
         */
        private fun parallelDownloads(
            left: Collection<DownloadEffect>,
            right: Collection<DownloadEffect>,
        ): List<DownloadEffect> {
            val leftShift = right.maxOfOrNull { it.concurrency } ?: 0
            val rightShift = left.maxOfOrNull { it.concurrency } ?: 0
            return normalize(
                left.map { it.copy(concurrency = it.concurrency + leftShift) } +
                    right.map { it.copy(concurrency = it.concurrency + rightShift) },
            )
        }

        /**
         * Normalizes obligations after completing and escaping components have
         * been materialized together.
         */
        private fun normalizeMaterialized(
            downloads: Collection<DownloadEffect>,
        ): List<DownloadEffect> {
            val normalized = mutableListOf<DownloadEffect>()
            downloads.forEach { download ->
                var merged = download
                var index = 0
                while (index < normalized.size) {
                    val candidate = normalized[index]
                    when {
                        candidate.materiallyCovers(merged) -> {
                            normalized[index] = candidate.mergeSelfBound(merged)
                            return@forEach
                        }
                        merged.materiallyCovers(candidate) -> {
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

    /** Returns entries whose work completes with the current call. */
    private fun completingDownloads(): List<DownloadEffect> =
        downloads.filter { it.lifetime == DownloadLifetime.COMPLETES_WITH_CALL }

    /** Returns entries whose work may outlive the current call. */
    private fun escapingDownloads(): List<DownloadEffect> =
        downloads.filter { it.lifetime == DownloadLifetime.MAY_OUTLIVE_CALL }

    /** Materializes completing and escaping entries as mutually parallel work. */
    private fun materializedDownloads(): List<DownloadEffect> =
        parallelDownloads(completingDownloads(), escapingDownloads())

    /** Returns whether this lifetime is at least as conservative as [other]. */
    private fun DownloadLifetime.covers(other: DownloadLifetime): Boolean =
        this == other || this == DownloadLifetime.MAY_OUTLIVE_CALL

}
