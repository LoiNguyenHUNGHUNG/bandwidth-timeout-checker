package io.github.loinguyen.bandwidth.annotations

/**
 * Declares a primitive network operation.
 *
 * The timeout is the complete-call deadline used for the transfer, not merely
 * a connect or read-idle timeout.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class NetworkDownload(
    public val maxBytes: Long,
    public val completeTimeoutMillis: Long,
)

/**
 * Marks a function as an application root invoked by a framework.
 *
 * The checker analyzes one invocation even when ordinary source code never
 * calls the function. All marked roots are conservatively composed in
 * parallel. Repeated framework callbacks registered by a root, such as Ktor
 * request handlers, are modeled separately at their registration calls.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class EntryPoint

/**
 * One download entry in a [BandwidthEffect] contract.
 *
 * [mayOutliveCall] means work represented by this entry may remain active after
 * the annotated function or callback returns. [selfBound] declares a bound
 * shared by repeated instances of this download kind; zero denotes the default
 * bound infinity. A positive value is a trusted contract that runtime
 * configuration enforces the declared finite limit.
 */
@Retention(AnnotationRetention.BINARY)
public annotation class BandwidthDownload(
    public val rMaxBytesPerSecond: Long,
    public val nMax: Int,
    public val mayOutliveCall: Boolean = false,
    public val selfBound: Int = 0,
)

/**
 * One occurrence of a polymorphic effect in a [BandwidthEffect] contract.
 *
 * A named occurrence on a function-valued parameter binds the effect of the
 * supplied callback. An occurrence with the same [name] on the enclosing
 * function substitutes that callback effect into the function call's effect.
 * [mayOutliveCall] conservatively changes the substituted effect's lifetime;
 * it does not repeat the effect or impose a concurrency bound. Function-level
 * occurrences are composed sequentially in declaration order, so a name may
 * occur more than once when a callback is invoked more than once.
 */
@Retention(AnnotationRetention.BINARY)
public annotation class BandwidthEffectVariable(
    public val name: String,
    public val mayOutliveCall: Boolean = false,
)

/**
 * Conservative list of download effects for an opaque function, higher-order
 * input, or returned function type whose body is unavailable to the checker.
 *
 * [rMaxBytesPerSecond] and [nMax] remain as source-compatible shorthand for one
 * completing entry. New contracts can retain rate/concurrency/lifetime
 * correlation in [downloads]. [variables] makes a higher-order contract
 * polymorphic in the effects of its function-valued inputs.
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.BINARY)
public annotation class BandwidthEffect(
    public val rMaxBytesPerSecond: Long = 0,
    public val nMax: Int = 0,
    public val downloads: Array<BandwidthDownload> = [],
    public val variables: Array<BandwidthEffectVariable> = [],
)

/**
 * Programmer assertion that the annotated try/catch expression implements
 * comparable network alternatives.
 *
 * The first checker version records this assertion but still joins both paths
 * conservatively. Recovery-specific checking is a later milestone.
 */
@Target(AnnotationTarget.EXPRESSION)
@Retention(AnnotationRetention.SOURCE)
public annotation class BandwidthAlternative

/**
 * Marks a CoroutineScope whose launched network bodies will eventually be
 * rewritten to run through a gate with [k] permits.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
public annotation class BoundedScope(
    public val k: Int,
)

/**
 * Declares the maximum number of network requests a client can execute at
 * once. This annotation is a trusted contract that runtime configuration
 * establishes the same bound, for example through `OkHttpClient`'s
 * `Dispatcher.maxRequests`.
 */
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
public annotation class BoundedClient(
    public val k: Int,
)
