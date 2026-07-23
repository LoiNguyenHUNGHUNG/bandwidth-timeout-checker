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
 * Conservative effect contract for an opaque function, higher-order input, or
 * returned function type whose body is unavailable to the checker.
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.BINARY)
public annotation class BandwidthEffect(
    public val rMaxBytesPerSecond: Long,
    public val nMax: Int,
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
