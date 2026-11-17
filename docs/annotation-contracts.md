# Download annotations and effect contracts

`@NetworkDownload(maxBytes = s, completeTimeoutMillis = t)` supplies the
singleton contract `{(1000*s/t, 1)}` in bytes per second. When a source body is
available, the checker verifies that its inferred effect is covered by this
contract. This is the same subtyping check used for `@BandwidthEffect`.
At call sites, the declared contract is used. The checker also checks available
implementations against contracts on overridden methods.

## A primitive library adapter

```kotlin
import io.github.loinguyen.bandwidth.annotations.NetworkDownload

// A third-party API whose implementation is unavailable to the checker.
interface HttpLibrary {
    suspend fun fetchBody(url: String): ByteArray
}

@NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
suspend fun download(http: HttpLibrary, url: String): ByteArray =
    http.fetchBody(url)
```

This declaration contributes `{(1000, 1)}`. It assumes that the library finishes
or cancels its transfer before returning, uses the stated whole-call timeout,
and stays within the size bound. The checker cannot establish those facts from
an opaque implementation. Annotate the adapter once; its callers need no
duplicate primitive annotations.

## A sequential wrapper that fits

```kotlin
@NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
suspend fun sequential(http: HttpLibrary, url: String) {
    download(http, url)
    download(http, url)
}
```

The inferred body effect is `{(1000, 1)}`, so the contract passes. It bounds
required rates and concurrency; the checker does not derive a new whole-call
deadline for the wrapper from these two calls. Usually, omit this wrapper
annotation and let the checker infer the effect.

## Parallel work needs a covering effect

```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
suspend fun rejected(http: HttpLibrary, url: String) = coroutineScope {
    awaitAll(async { download(http, url) }, async { download(http, url) })
    Unit
}
```

This is rejected: the body has effect `{(1000, 2)}`, which is not covered by
`{(1000, 1)}`. Increasing the annotated size cannot repair the missing
concurrency. Either remove the annotation or write the checked contract:

```kotlin
import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

@BandwidthEffect(rMaxBytesPerSecond = 1_000, nMax = 2)
suspend fun parallel(http: HttpLibrary, url: String) = coroutineScope {
    awaitAll(async { download(http, url) }, async { download(http, url) })
    Unit
}
```

The parallel wrapper requires 2,000 bytes/s in isolation. The same check rejects
a sequential body whose required rate exceeds the annotated rate. Untimed
downloads still count toward concurrency even though their own required rate
is zero.

## Opaque aggregate operations

An external library can itself orchestrate parallel or escaping transfers.
Supply a trusted aggregate `@BandwidthEffect` contract at that boundary; a
completing singleton cannot express that concurrency or escaping work. Source composites
use checked contracts, while third-party aggregate summaries remain assumptions.

Both annotations are retained in binaries. Inferred summaries are local to the
current compilation. Returned functions and Flows carry separate latent effects;
an invocation contract does not make their later network work disappear.
