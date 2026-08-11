# Bandwidth Timeout Checker

Research prototype for checking whether network transfer sizes, complete-call
timeouts, and program concurrency are compatible with an expected bandwidth
budget.

The project is based on *Bandwidth-Timeout Verification via Quantitative
Effects*. It uses the paper's finite Pareto sets of rate/concurrency pairs:

```text
download(s, t) = {(s / t, 1)}
sequential(Phi1, Phi2) = Norm(Phi1 union Phi2)
parallel(Phi1, Phi2) =
  Norm(
    {(r, n + K(Phi2)) | (r, n) in Phi1} union
    {(r, n + K(Phi1)) | (r, n) in Phi2}
  )
ReqBW(Phi) = max {(r * n) | (r, n) in Phi}
```

The current milestone builds the mathematical core, public annotations,
sequential Kotlin effect inference, Gradle integration, and runtime support for
a future bounded-scope rewrite. It does not yet infer structured coroutine
parallelism or transform coroutine launches.

## Modules

- `checker-core`: exact quantitative-effect abstract domain and composition
  operations.
- `plugin-annotations`: annotations used at network and opaque API boundaries.
- `compiler-plugin`: K2 compiler registration, annotation validation, and a
  native Kotlin FIR visitor that augments resolved Kotlin types with effects
  for calls, sequence, functions,
  ordinary branches, and `try/catch`.
- `gradle-plugin`: adds the compiler plugin and annotation dependency to Kotlin
  compilations.
- `runtime`: semaphore gate targeted by the future `@BoundedScope` IR rewrite.
- `integration-tests`: Kotlin source compiled with the built plugin as a CI
  discovery and annotation smoke test.

## Annotation sketch

```kotlin
@NetworkDownload(maxBytes = 8_000_000, completeTimeoutMillis = 10_000)
suspend fun downloadImage(url: String): ByteArray

fun applyNetworkCallback(
    @BandwidthEffect(rMaxBytesPerSecond = 800_000, nMax = 1)
    callback: suspend () -> Unit,
)

@BoundedScope(k = 4)
val downloadScope = viewModelScope

@BoundedClient(k = 4)
val imageClient = OkHttpClient.Builder()
    .dispatcher(Dispatcher().apply { maxRequests = 4 })
    .build()

@BandwidthAlternative
try {
    downloadLargeImage()
} catch (_: TimeoutCancellationException) {
    downloadSmallImage()
}
```

`@NetworkDownload` applies only at primitive/library boundaries.
`@BandwidthEffect` supplies a conservative list of download effects when a
higher-order or library body is unavailable. The original
`rMaxBytesPerSecond`/`nMax` arguments remain shorthand for one completing
download effect. Contracts that may outlive their call retain that fact:

```kotlin
@BandwidthEffect(
    downloads = [
        BandwidthDownload(
            rMaxBytesPerSecond = 800_000,
            nMax = 4,
            mayOutliveCall = true,
            selfBound = 4,
        ),
    ],
)
fun startBackgroundSync()
```

The checker preserves the individual rate, concurrency, self-bound, and
lifetime fields instead of collapsing them to one pair. Set `selfBound` on an
opaque download entry when a runtime client or scheduler enforces a shared
limit across repeated invocations; zero leaves the bound unspecified.

`@BoundedClient(k)` attaches a configured self bound to instances of one
primitive download kind. A raw download carries
`(r, n, selfBound=k, lifetime)`.
Recognized concurrency constructs compute `n` with ordinary parallel algebra.
Escaping coroutine work also uses self bounds. A `launch` or `async` through
an explicit or otherwise unproven scope receiver may outlive its expression
and function, so its body is summarized with unknown repetition and retained
as long-lived work. Such a body requires a bounded client for every download.
An unqualified builder directly inside a recognized `coroutineScope` or
`withContext` remains structured. The checker trusts the client configuration;
for OkHttp, set the matching `Dispatcher.maxRequests` value.

For now, `@BandwidthAlternative` is a trusted assertion attached to the whole
`try/catch` expression, but recovery paths are still joined conservatively.
This avoids assigning zero bandwidth to `try { download() } catch { showError() }`
before the alternative relation is formalized.

Visible function bodies are inferred. A `@BandwidthEffect` on a visible function
is checked as an interface contract, including lifetime, while calls across
opaque boundaries use the declared effect list. Invoked higher-order parameters
require their own `@BandwidthEffect`, and visible callback bodies are checked
against that declaration.

Higher-order effects remain latent while lambdas and function references are
stored, aliased, returned, or captured, and are charged only when the function
value is invoked. Callback factories can therefore be inferred without internal
annotations. Opaque APIs that return callbacks must annotate the returned
function type. Function values stored in object fields or collections are not
yet tracked and require an explicit boundary.

To print inferred effects during a Gradle compilation:

```kotlin
bandwidthChecker {
    reportEffects.set(true)
}
```

The recursion-free milestone rejects unannotated recursion, effectful loops,
and effectful callbacks passed to opaque higher-order APIs without a parameter
contract. Trusted library models cover sequential `forEach` callbacks and
AndroidX `traceAsync`; these invoke a visible callback once for peak-bandwidth
inference rather than treating it as concurrent work.

## Candidate Android case studies

A primitive transfer without a complete-call deadline is modeled with
`timeout = infinity`, so `rate = size / timeout = 0`. It contributes no direct
bandwidth requirement, but it still counts as concurrent work when it overlaps
a finite-deadline transfer. If every transfer in an analyzed application has an
infinite deadline, `ReqBW = 0` is correct but vacuous; the checker should report
that no finite completion deadlines were found instead of presenting the result
as a substantive verification.

The following open-source applications contain real complete-call deadlines
and network-resilience mechanisms. Source links identify the files to use when
building integration fixtures.

### Primary subjects

#### Neko

Neko is the strongest first end-to-end subject. Its shared OkHttp configuration
sets a one-minute `callTimeout`, 15-second connect/read timeouts, a global
dispatcher bound of 30, a per-host bound of 20, a 5 MiB cache, request
priorities, and request-rate limits. Image attempts are retried three times with
2, 4, and 8 second delays, while the download worker responds to connectivity
and unmetered-network changes.

- [NetworkHelper.kt](https://github.com/nekomangaorg/Neko/blob/main/app/src/main/java/eu/kanade/tachiyomi/network/NetworkHelper.kt)
- [Downloader.kt](https://github.com/nekomangaorg/Neko/blob/main/app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt)
- [DownloadJob.kt](https://github.com/nekomangaorg/Neko/blob/main/app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadJob.kt)

Candidate workflows are one manga page, one concurrently downloaded chapter,
an image retry chain, and multiple chapters sharing the bounded client. The
complete deadline applies to each primitive attempt; sequential retries extend
recovery time without increasing peak concurrency.

#### Nextcloud

Nextcloud is the best mixed finite/infinite subject. Its ordinary API client
uses 60-second connect/read timeouts and a 120-second complete-call timeout.
Large file downloads deliberately derive size-sensitive read timeouts and set
`callTimeout(0)`, while chunked uploads remember completed chunks and resume
from the next missing byte. The expected result is a positive bandwidth
requirement for ordinary API calls and rate zero for open-ended file transfers.

- [NextcloudClient.kt](https://github.com/nextcloud/android-library/blob/master/library/src/main/java/com/nextcloud/common/NextcloudClient.kt)
- [DownloadFileRemoteOperation.kt](https://github.com/nextcloud/android-library/blob/master/library/src/main/java/com/owncloud/android/lib/resources/files/DownloadFileRemoteOperation.kt)
- [ChunkedFileUploadRemoteOperation.java](https://github.com/nextcloud/android-library/blob/master/library/src/main/java/com/owncloud/android/lib/resources/files/ChunkedFileUploadRemoteOperation.java)
- [AutoUploadWorker.kt](https://github.com/nextcloud/android/blob/master/app/src/main/java/com/nextcloud/client/jobs/autoUpload/AutoUploadWorker.kt)

#### Fedilab

Fedilab uses a 60-second complete timeout for ordinary Mastodon requests and a
120-second timeout for posting. Its network-constrained background worker
fetches at most ten timeline pages and advances from the newest cached status,
making it useful for finite calls inside repeated, cache-backed feed loading.

- [Helper.java](https://github.com/stom79/Fedilab/blob/develop/app/src/main/java/app/fedilab/android/mastodon/helper/Helper.java)
- [FetchHomeWorker.java](https://github.com/stom79/Fedilab/blob/develop/app/src/main/java/app/fedilab/android/mastodon/jobs/FetchHomeWorker.java)

### Additional subjects

- **Jellyfin:** ordinary SDK API requests have a 30-second complete deadline,
  a 6-second connection timeout, and a 30-second socket timeout. Coil images
  and Media3 streams are separate paths and must not inherit the API deadline.
  See [ApiModule.kt](https://github.com/jellyfin/jellyfin-android/blob/master/app/src/main/java/org/jellyfin/mobile/app/ApiModule.kt),
  [HttpClientOptions.kt](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/v1.7.1/jellyfin-api/src/commonMain/kotlin/org/jellyfin/sdk/api/client/HttpClientOptions.kt),
  and [OkHttpFactory.kt](https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/v1.7.1/jellyfin-api-okhttp/src/jvmMain/kotlin/org/jellyfin/sdk/api/okhttp/OkHttpFactory.kt).
- **Proton VPN:** API calls use a 30-second complete timeout, 5-second connect
  timeout, and 20-second read/write timeouts. DNS-over-HTTPS and alternative
  routing provide route-level resilience. See
  [VpnApiClient.kt](https://github.com/ProtonVPN/android-app/blob/master/app/src/main/java/com/protonvpn/android/api/VpnApiClient.kt),
  [DohEnabled.kt](https://github.com/ProtonVPN/android-app/blob/master/app/src/main/java/com/protonvpn/android/api/DohEnabled.kt),
  and [ShouldSkipPrimaryApiRoute.kt](https://github.com/ProtonVPN/android-app/blob/master/app/src/main/java/com/protonvpn/android/appconfig/usecase/ShouldSkipPrimaryApiRoute.kt).
- **Readrops:** its Retrofit clients use a one-minute complete timeout. RSS
  synchronization is network-constrained, database-backed, and processes feed
  metadata in bounded batches. See
  [ApiModule.kt](https://github.com/readrops/Readrops/blob/develop/api/src/main/java/com/readrops/api/ApiModule.kt),
  [SyncWorker.kt](https://github.com/readrops/Readrops/blob/develop/app/src/main/java/com/readrops/app/sync/SyncWorker.kt),
  and [Synchronizer.kt](https://github.com/readrops/Readrops/blob/develop/app/src/main/java/com/readrops/app/sync/Synchronizer.kt).

### Infinite-timeout baselines

These applications primarily configure connection or per-read stall timeouts,
not complete-call deadlines. They remain useful for confirming that the checker
reports a vacuous result rather than treating a stall timeout as a completion
deadline.

- [Now in Android NetworkModule.kt](https://github.com/android/nowinandroid/blob/main/core/network/src/main/kotlin/com/google/samples/apps/nowinandroid/core/network/di/NetworkModule.kt)
- [AntennaPod AntennapodHttpClient.java](https://github.com/AntennaPod/AntennaPod/blob/develop/net/common/src/main/java/de/danoeh/antennapod/net/common/AntennapodHttpClient.java)
- [Signal SignalRestClient.kt](https://github.com/signalapp/Signal-Android/blob/main/lib/network/src/main/java/org/signal/network/rest/SignalRestClient.kt)
- [NewPipe DownloaderImpl.java](https://github.com/TeamNewPipe/NewPipe/blob/dev/app/src/main/java/org/schabi/newpipe/DownloaderImpl.java)
- [NewPipe DownloadMission.java](https://github.com/TeamNewPipe/NewPipe/blob/dev/app/src/main/java/us/shandian/giga/get/DownloadMission.java)

## Build

Requirements: JDK 21 or newer.

```shell
./gradlew build
```

The FIR adapter can be compiled and tested against a supported Kotlin compiler
version without changing source:

```shell
./gradlew clean build -PkotlinVersion=2.3.0
./gradlew clean build -PkotlinVersion=2.4.10
```

The default remains the version in `gradle/libs.versions.toml`. CI verifies
both supported compiler lines. This override changes the Kotlin Gradle plugin
and all `org.jetbrains.kotlin` build dependencies together; compiler-plugin
artifacts must not mix versions.

## Status

- [x] Exact rational rates and Pareto-normalized effects
- [x] Sequential, conditional, parallel, and bounded-replication core operations
- [x] Boundary annotations without priorities
- [x] Compiler and Gradle plugin registration
- [x] Runtime semaphore gate
- [x] Source-located annotation and contract diagnostics
- [x] Native Kotlin FIR visitor for sequential calls, functions, branches, and
  `try/catch`
- [x] Higher-order parameter contracts and visible callback checking
- [x] Latent effects for stored, aliased, captured, and returned function values
- [x] FIR-native annotation, contract, recursion, and loop diagnostics
- [x] Structured `coroutineScope`/`withContext` inference with sequential parent
  work, conservative `launch`/`async` overlap, and inline `awaitAll`
- [x] Sequential `chunked(...).forEach` callback inference
- [x] Version-selected FIR adapters and CI coverage for Kotlin 2.3 and 2.4
- [ ] Path-sensitive, rate-sensitive branch refinement
- [ ] Sound `@BandwidthAlternative` recovery semantics
- [ ] `@BoundedScope` alias checks and IR rewriting
- [ ] Android fixture application and evaluation on open-source apps

See [docs/architecture.md](docs/architecture.md) for the planned pipeline and
milestones.

## Origin

The initial compiler-plugin wiring was scaffolded from JetBrains'
[Kotlin compiler plugin template](https://github.com/Kotlin/compiler-plugin-template).
The repository retains its Apache-2.0 license.
