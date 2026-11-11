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
 * An immutable finite set of lifetime-tagged primitive download effects.
 *
 * [analysisMode] selects normalization/composition for this effect. Existing
 * factory overloads retain detailed analysis; alternate modes are explicit.
 *
 * Stored concurrency is local to one lifetime component. Public obligations
 * materialize completing and escaping work in parallel exactly once.
 */
public class NetworkEffect private constructor(
    private val downloads: List<DownloadEffect>,
    /** Immutable algebra selected when this effect was created. */
    public val analysisMode: AnalysisMode = AnalysisMode.DETAILED,
    /** All work in this effect belongs to one shared, bounded invocation region. */
    private val concurrentInvocationBound: Int? = null,
) {
    /** Final bandwidth obligations after lifetime overlap is materialized. */
    public val obligations: List<DownloadEffect>
        get() = normalizeMaterialized(materializedDownloads(), analysisMode).sortedWith(
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
    public fun then(other: NetworkEffect): NetworkEffect {
        requireCompatibleMode(other)
        if (downloads.isEmpty()) return other
        if (other.downloads.isEmpty()) return this
        return fromComponents(
            completing = normalize(completingDownloads() + other.completingDownloads(), analysisMode),
            escaping = parallelDownloads(escapingDownloads(), other.escapingDownloads(), analysisMode),
            analysisMode = analysisMode,
        )
    }

    /** Parallel composition from Table 2, applied within each lifetime component. */
    public fun parallel(other: NetworkEffect): NetworkEffect {
        requireCompatibleMode(other)
        if (downloads.isEmpty()) return other
        if (other.downloads.isEmpty()) return this
        return fromComponents(
            completing = parallelDownloads(
                completingDownloads(),
                other.completingDownloads(),
                analysisMode,
            ),
            escaping = parallelDownloads(
                escapingDownloads(),
                other.escapingDownloads(),
                analysisMode,
            ),
            analysisMode = analysisMode,
        )
    }

    /** Alternative-path join: only one operand executes. */
    public fun choice(other: NetworkEffect): NetworkEffect {
        requireCompatibleMode(other)
        if (downloads.isEmpty()) return other
        if (other.downloads.isEmpty()) return this
        return of(downloads + other.downloads, analysisMode)
    }

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
        return of(downloads.map { it.copy(selfBound = selfBound) }, analysisMode)
            .withInvocationBound(concurrentInvocationBound)
    }

    /**
     * Records that a shared gate admits at most [maxConcurrentInvocations]
     * active invocations of this whole effect without changing the effect of
     * one invocation. An entry exposing `n` downloads per invocation therefore
     * carries a repetition bound of `maxConcurrentInvocations * n`.
     *
     * An existing tighter bound, such as a client-wide limit, is preserved by
     * taking the minimum; a missing bound denotes infinity.
     * The whole-region bound becomes active only if an enclosing construct
     * applies unknown overlapping repetition. It then realizes finite parallel
     * composition of [maxConcurrentInvocations] copies, preserving the body's
     * rate/concurrency correlations without changing a single call.
     *
     * Composing two nonempty effects drops this whole-region information: the
     * effects may use independent gates. Their per-download bounds remain as a
     * conservative fallback for later repetition.
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
            analysisMode,
        ).withInvocationBound(
            concurrentInvocationBound?.coerceAtMost(maxConcurrentInvocations)
                ?: maxConcurrentInvocations,
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
     * A shared whole-invocation bound uses finite parallel composition: an
     * obligation `(r, n)` becomes `(r, n + (k - 1) * K)`, where `K` is the body's
     * maximum concurrency and `k` bounds overlapping invocations. Independent
     * per-download self bounds additionally cap the total concurrency.
     *
     * Without a whole-invocation bound, each retained download may reach its
     * own [DownloadEffect.selfBound], so their bounds are summed. Self bounds
     * stay on the returned downloads for an enclosing repetition boundary;
     * the whole-invocation bound is consumed by this repetition.
     */
    public fun withUnknownRepetition(
        lifetime: DownloadLifetime = DownloadLifetime.COMPLETES_WITH_CALL,
    ): NetworkEffect {
        require(hasSelfBoundForEveryDownload) {
            "unknown repetition requires a self bound for every download"
        }
        val materialized = obligations
        val selfBoundTotal = materialized.sumOf { requireNotNull(it.selfBound).toLong() }
        val parallelShift = concurrentInvocationBound?.let { bound ->
            (bound - 1).toLong() * (materialized.maxOfOrNull { it.concurrency } ?: 0)
        }
        return of(
            materialized.map {
                val concurrency = parallelShift?.let { shift ->
                    minOf(it.concurrency.toLong() + shift, selfBoundTotal)
                } ?: selfBoundTotal
                it.copy(concurrency = Math.toIntExact(concurrency), lifetime = lifetime)
            },
            analysisMode,
        )
    }

    /** Materializes this whole effect at a new enclosing lifetime boundary. */
    public fun withLifetime(lifetime: DownloadLifetime): NetworkEffect =
        of(obligations.map { it.copy(lifetime = lifetime) }, analysisMode)
            .withInvocationBound(concurrentInvocationBound)

    /** Projection used by structured-coroutine phase tracking. */
    public fun completingOnly(): NetworkEffect = of(completingDownloads(), analysisMode)
        .withInvocationBound(concurrentInvocationBound)

    /** Projection used by structured-coroutine phase tracking. */
    public fun escapingOnly(): NetworkEffect = of(escapingDownloads(), analysisMode)
        .withInvocationBound(concurrentInvocationBound)

    /** Attaches a bound only while it still covers every download in the effect. */
    private fun withInvocationBound(bound: Int?): NetworkEffect =
        if (downloads.isEmpty() || bound == null) this else NetworkEffect(downloads, analysisMode, bound)

    /** `ReqBW(Phi) = max { r * n | (r, n) in Phi }`. */
    public fun requiredBandwidthBytesPerSecond(): Rational {
        return obligations.maxOfOrNull {
            it.requiredRateBytesPerSecond * it.concurrency
        }
            ?: Rational.ZERO
    }

    /**
     * Independent-maxima baseline: `max(r) * max(n)` over the final obligations.
     *
     * This shares the inferred concurrency and lifetime assumptions with the
     * pair-set result, but discards the correlation between rate and overlap.
     * The two maxima may therefore come from different sequential phases.
     */
    public fun independentMaximaBandwidthBytesPerSecond(): Rational {
        val materialized = obligations
        val maximumRate = materialized.maxOfOrNull { it.requiredRateBytesPerSecond }
            ?: Rational.ZERO
        val maximumConcurrency = materialized.maxOfOrNull { it.concurrency } ?: 0
        return maximumRate * maximumConcurrency
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

    /** Returns whether [other] has the same component entries and invocation bound. */
    public override fun equals(other: Any?): Boolean =
        other is NetworkEffect && downloads == other.downloads &&
            analysisMode == other.analysisMode &&
            concurrentInvocationBound == other.concurrentInvocationBound

    /** Returns a hash code consistent with [equals]. */
    public override fun hashCode(): Int =
        31 * (31 * downloads.hashCode() + analysisMode.hashCode()) +
            (concurrentInvocationBound?.hashCode() ?: 0)

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
            of(downloads, AnalysisMode.DETAILED)

        /** Creates an effect using the explicitly selected immutable algebra. */
        public fun of(
            downloads: Collection<DownloadEffect>,
            analysisMode: AnalysisMode,
        ): NetworkEffect = if (downloads.isEmpty()) EMPTY else
            NetworkEffect(normalize(downloads, analysisMode), analysisMode)

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
        public fun download(maxBytes: Long, completeTimeoutMillis: Long): NetworkEffect =
            download(maxBytes, completeTimeoutMillis, AnalysisMode.DETAILED)

        /** Creates a primitive transfer in the selected algebra. */
        public fun download(
            maxBytes: Long,
            completeTimeoutMillis: Long,
            analysisMode: AnalysisMode,
        ): NetworkEffect {
            require(maxBytes >= 0) { "maximum transfer size must be non-negative" }
            require(completeTimeoutMillis > 0) { "complete-call timeout must be positive" }
            val rate = Rational.of(
                maxBytes.toBigInteger() * BigInteger.valueOf(1_000),
                completeTimeoutMillis.toBigInteger(),
            )
            return of(listOf(DownloadEffect(rate, concurrency = 1)), analysisMode)
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
        ): NetworkEffect = summary(rMaxBytesPerSecond, nMax, lifetime, AnalysisMode.DETAILED)

        /** Creates a declared summary in the selected algebra. */
        public fun summary(
            rMaxBytesPerSecond: Long,
            nMax: Int,
            lifetime: DownloadLifetime = DownloadLifetime.COMPLETES_WITH_CALL,
            analysisMode: AnalysisMode,
        ): NetworkEffect {
            require(rMaxBytesPerSecond >= 0) { "rMax must be non-negative" }
            require(nMax >= 0) { "nMax must be non-negative" }
            return if (nMax == 0) EMPTY else of(
                listOf(DownloadEffect(Rational.of(rMaxBytesPerSecond), nMax, lifetime = lifetime)),
                analysisMode,
            )
        }

        /**
         * Pareto-normalizes [downloads] independently within each lifetime and
         * combines self bounds when entries collapse.
         */
        private fun normalize(
            downloads: Collection<DownloadEffect>,
            analysisMode: AnalysisMode,
        ): List<DownloadEffect> {
            if (analysisMode == AnalysisMode.SIMPLIFIED) return collapse(downloads)
            if (analysisMode == AnalysisMode.DIAGNOSTIC) return retainDistinct(downloads)
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

        /** The baseline independently maximizes rate and concurrency at every merge. */
        private fun collapse(downloads: Collection<DownloadEffect>): List<DownloadEffect> =
            downloads.groupBy { it.lifetime }.values.map { group ->
                DownloadEffect(
                    requiredRateBytesPerSecond = group.maxOf { it.requiredRateBytesPerSecond },
                    concurrency = group.maxOf { it.concurrency },
                    selfBound = if (group.any { it.selfBound == null }) null else
                        Math.toIntExact(group.sumOf { requireNotNull(it.selfBound).toLong() }),
                    lifetime = group.first().lifetime,
                )
            }

        /** Placeholder rates are identities in diagnostics, never numeric dominance evidence. */
        private fun retainDistinct(downloads: Collection<DownloadEffect>): List<DownloadEffect> {
            val retained = mutableListOf<DownloadEffect>()
            downloads.forEach { download ->
                val index = retained.indexOfFirst { it.hasSameRateAndConcurrency(download) }
                if (index < 0) retained += download
                else retained[index] = retained[index].mergeSelfBound(download)
            }
            return retained
        }

        /** Creates an effect from already separated completing and escaping components. */
        private fun fromComponents(
            completing: Collection<DownloadEffect>,
            escaping: Collection<DownloadEffect>,
            analysisMode: AnalysisMode,
        ): NetworkEffect = of(completing + escaping, analysisMode)

        /**
         * Applies the parallel-composition concurrency shifts to [left] and
         * [right], then normalizes the result.
         */
        private fun parallelDownloads(
            left: Collection<DownloadEffect>,
            right: Collection<DownloadEffect>,
            analysisMode: AnalysisMode,
        ): List<DownloadEffect> {
            val leftShift = right.maxOfOrNull { it.concurrency } ?: 0
            val rightShift = left.maxOfOrNull { it.concurrency } ?: 0
            return normalize(
                left.map { it.copy(concurrency = it.concurrency + leftShift) } +
                    right.map { it.copy(concurrency = it.concurrency + rightShift) },
                analysisMode,
            )
        }

        /**
         * Normalizes obligations after completing and escaping components have
         * been materialized together.
         */
        private fun normalizeMaterialized(
            downloads: Collection<DownloadEffect>,
            analysisMode: AnalysisMode,
        ): List<DownloadEffect> {
            if (analysisMode == AnalysisMode.DIAGNOSTIC) return retainDistinct(downloads)
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
        parallelDownloads(completingDownloads(), escapingDownloads(), analysisMode)

    /** Prevents accidental combinations of summaries produced by different analyses. */
    private fun requireCompatibleMode(other: NetworkEffect) {
        require(downloads.isEmpty() || other.downloads.isEmpty() || analysisMode == other.analysisMode) {
            "Cannot compose effects from different analysis modes"
        }
    }

    /** Returns whether this lifetime is at least as conservative as [other]. */
    private fun DownloadLifetime.covers(other: DownloadLifetime): Boolean =
        this == other || this == DownloadLifetime.MAY_OUTLIVE_CALL

}
