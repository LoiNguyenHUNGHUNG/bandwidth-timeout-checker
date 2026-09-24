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
 * Universally quantifies the named bandwidth-effect variables of a function.
 *
 * Each name is scoped to the annotated function, just as a type parameter is
 * scoped to its declaration. Higher-order parameters and opaque invocation
 * contracts refer to quantified variables with `@BandwidthEffect("E")`. The
 * checker substitutes callback effects for those variables at call sites.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class BandwidthVariable(
    public vararg val names: String,
)

/**
 * Conservative list of download effects for an opaque function, higher-order
 * input, or returned function type whose body is unavailable to the checker.
 *
 * A non-empty [variable] assigns a quantified effect to a higher-order input or
 * to the invocation of an opaque function. For example, placing
 * `@BandwidthEffect("E")` on both an `install` function and its callback
 * parameter gives it the type `(A -[E]-> B) -[E]-> Unit`. Declaration-level
 * symbolic contracts are trusted library boundaries; ordinary visible
 * polymorphic functions should omit them and let the checker infer their body.
 * Concrete contracts use [rMaxBytesPerSecond], [nMax], or [downloads].
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.BINARY)
public annotation class BandwidthEffect(
    public val variable: String = "",
    public val rMaxBytesPerSecond: Long = 0,
    public val nMax: Int = 0,
    public val downloads: Array<BandwidthDownload> = [],
)

/**
 * Marks a callback expression or function declaration that a framework may
 * invoke repeatedly and concurrently.
 *
 * An annotated expression is materialized at its registration site. An
 * annotated declaration is added directly to the virtual application root,
 * which models callbacks invoked from opaque library code. In either form, the
 * body becomes long-lived work and passes through the checker's ordinary
 * unknown-repetition rule. Consequently, every network operation must obtain a
 * finite self bound from a visible semaphore, bounded client, or another
 * trusted runtime limit.
 */
@Target(AnnotationTarget.EXPRESSION, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class Handler

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
