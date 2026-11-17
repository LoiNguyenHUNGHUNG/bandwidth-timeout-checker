# Download annotations and effect contracts

`@NetworkDownload(maxBytes = s, completeTimeoutMillis = t)` annotates a call
expression performing one primitive transfer at an opaque library boundary.
It contributes `{(1000*s/t, 1)}` in bytes per second. The receiver and arguments
retain their own evaluation effects. This annotation is not a function
contract; function contracts use `@BandwidthEffect`.

## Annotate the primitive call

Suppose a compiled HTTP library provides `fetchBody(url): ByteArray` and
completes or cancels its one transfer before returning:

```kotlin
import io.github.loinguyen.bandwidth.annotations.NetworkDownload

suspend fun download(http: HttpLibrary, url: String): ByteArray {
    val bytes = @NetworkDownload(
        maxBytes = 1_000,
        completeTimeoutMillis = 1_000,
    ) http.fetchBody(url)
    return bytes
}
```

The annotated expression contributes `{(1000, 1)}`. Its size, complete-call
timeout, and single-transfer behavior are trusted assumptions about the
library operation. The annotation does not install a timer or prove those
facts. Source-function effects are composed normally; use their bodies or a
`@BandwidthEffect` contract to describe aggregate work.

The wrapper `download` has no annotation. Its effect is inferred from its body,
and callers use that summary without repeating the call-site annotation.

Omit both bounds, or set both to `None`, for an untimed transfer with no size
bound. Its effect has rate zero and concurrency one, retaining interference
with timed downloads. A zero timeout or a partially specified bound is rejected.

## Infer ordinary wrappers

```kotlin
suspend fun sequential(http: HttpLibrary, url: String) {
    download(http, url)
    download(http, url)
}
```

The checker infers `{(1000, 1)}` for this sequential body. It summarizes peak
required rate and concurrency, without inventing a whole-wrapper deadline.
No function-level `@NetworkDownload` annotation is used.

## Check aggregate function contracts

```kotlin
import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@BandwidthEffect(rMaxBytesPerSecond = 1_000, nMax = 2)
suspend fun parallel(http: HttpLibrary, url: String) = coroutineScope {
    awaitAll(async { download(http, url) }, async { download(http, url) })
    Unit
}
```

The inferred body effect is `{(1000, 2)}`, requiring 2,000 bytes/s in isolation.
The declared `@BandwidthEffect` covers it. A contract with `nMax = 1` would
fail this check; raising a primitive's size does not cover missing concurrency.
The same coverage check applies to other concrete source-function and
interface contracts. Ordinary wrappers may omit the contract and use inference.

Opaque library operations that orchestrate parallel or escaping transfers
need trusted aggregate `@BandwidthEffect` contracts. A single-transfer
annotation cannot describe that aggregate behavior. Returned functions and
Flows also need their latent effects accounted for; an immediate invocation
contract does not make later network work disappear.

## Separate compilation

`@NetworkDownload` targets expressions and has `SOURCE` retention. Its call-site
information is consumed during inference. Explicit `@BandwidthEffect`
contracts are retained in compiled binaries, so a consumer of an annotated
library can use those contracts without the library's original call
annotations. Inferred summaries are cached within the current compilation;
they are not automatically exported. Add an explicit `@BandwidthEffect`
contract when a separately compiled consumer needs that summary.
