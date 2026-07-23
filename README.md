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

The current milestone builds the mathematical core, public annotations, Kotlin
compiler-plugin registration, Gradle integration, and runtime support for a
future bounded-scope rewrite. It does not yet infer effects from arbitrary
Kotlin bodies or transform coroutine launches.

## Modules

- `checker-core`: language-independent network IR and exact quantitative-effect
  implementation.
- `plugin-annotations`: annotations used at network and opaque API boundaries.
- `compiler-plugin`: K2 compiler registration and annotation validation; this
  becomes the Kotlin-to-network-IR frontend.
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

For now, `@BandwidthAlternative` is a trusted assertion attached to the whole
`try/catch` expression, but recovery paths are still joined conservatively.
This avoids assigning zero bandwidth to `try { download() } catch { showError() }`
before the alternative relation is formalized.

## Build

Requirements: JDK 21 or newer.

```shell
./gradlew build
```

## Status

- [x] Exact rational rates and Pareto-normalized effects
- [x] Sequential, conditional, parallel, and bounded-replication core nodes
- [x] Boundary annotations without priorities
- [x] Compiler and Gradle plugin registration
- [x] Runtime semaphore gate
- [ ] FIR diagnostics and symbol/effect inference
- [ ] Kotlin control-flow lowering, including coroutine structure
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
