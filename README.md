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
`@BandwidthEffect(rMaxBytesPerSecond, nMax)` supplies a conservative latent
effect when a higher-order or library body is unavailable. Other effects are
intended to be inferred.

`@BoundedClient(k)` attaches a configured runtime bound to instances of one
primitive download kind. A raw download begins as `(r, 1, selfBound=k)`.
Recognized Kotlin syntax supplies a known global concurrency `n`; an unknown
repetition boundary such as `forEach { viewModelScope.launch { ... } }` uses
`selfBound` as its finite `n`. The checker never treats `k` as a global app
bound: work from other clients may overlap and is composed in parallel.
Unknown work without a client bound is rejected. The checker trusts the client
configuration; for OkHttp, set the matching `Dispatcher.maxRequests` value.

For now, `@BandwidthAlternative` is a trusted assertion attached to the whole
`try/catch` expression, but recovery paths are still joined conservatively.
This avoids assigning zero bandwidth to `try { download() } catch { showError() }`
before the alternative relation is formalized.

Visible function bodies are inferred. A `@BandwidthEffect` on a visible function
is checked as an interface contract, while calls across opaque boundaries use
the declared effect. Invoked higher-order parameters require their own
`@BandwidthEffect(rMaxBytesPerSecond, nMax)`, and visible callback bodies are
checked against that declaration.

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
