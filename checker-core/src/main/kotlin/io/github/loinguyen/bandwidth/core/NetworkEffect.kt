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
 * [concurrency] is always established by the effect rules. [selfBound] is a
 * trusted bound for instances of this download kind. It is retained when an
 * enclosing unknown-repetition construct recalculates [concurrency]. [lifetime]
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

    internal fun dominates(other: DownloadEffect): Boolean {
        if (lifetime != other.lifetime) return false
        return requiredRateBytesPerSecond >= other.requiredRateBytesPerSecond &&
            concurrency >= other.concurrency &&
            (requiredRateBytesPerSecond > other.requiredRateBytesPerSecond ||
                concurrency > other.concurrency)
    }

    internal fun hasSameRateAndConcurrency(other: DownloadEffect): Boolean =
        lifetime == other.lifetime &&
        requiredRateBytesPerSecond == other.requiredRateBytesPerSecond &&
            concurrency == other.concurrency

    internal fun mergeSelfBound(other: DownloadEffect): DownloadEffect =
        copy(
            selfBound = when {
                selfBound == null || other.selfBound == null -> null
                else -> selfBound + other.selfBound
            },
        )

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

        private fun fromComponents(
            completing: Collection<DownloadEffect>,
            escaping: Collection<DownloadEffect>,
        ): NetworkEffect = NetworkEffect(normalize(completing + escaping))

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

    private fun completingDownloads(): List<DownloadEffect> =
        downloads.filter { it.lifetime == DownloadLifetime.COMPLETES_WITH_CALL }

    private fun escapingDownloads(): List<DownloadEffect> =
        downloads.filter { it.lifetime == DownloadLifetime.MAY_OUTLIVE_CALL }

    private fun materializedDownloads(): List<DownloadEffect> =
        parallelDownloads(completingDownloads(), escapingDownloads())

    private fun DownloadLifetime.covers(other: DownloadLifetime): Boolean =
        this == other || this == DownloadLifetime.MAY_OUTLIVE_CALL

}
