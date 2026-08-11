# Architecture and milestones

## Property

Given a closed network-relevant program with effect `Phi`, the checker computes
the implicit peak-bandwidth budget `ReqBW(Phi)`. Under the paper's fair-share
model, a configured bandwidth `B` is feasible when:

```text
ReqBW(Phi) <= B
```

This is not a general progress or user-visible fallback-correctness property.
It checks that declared transfer sizes and timeouts match the stated bandwidth
environment, accounting for program concurrency.

## Pipeline

```text
Kotlin source and library contracts
              |
              v
Kotlin FIR resolution: Gamma |- e : tau
              |
              v
FIR quantitative-effect visitor: Gamma |- e : tau |> Phi
  - primitive download -> singleton effect
  - sequence / choice -> sequential join
  - structured concurrency -> parallel composition
  - escaping coroutine work -> download effects tagged `MAY_OUTLIVE_CALL`
  - opaque call -> declared latent effect
              |
              v
FIR diagnostics, symbol summaries, and ReqBW reports

Kotlin IR
  |
  +--> later: rewrite @BoundedScope launches through runtime gates
```

The quantitative-effect domain has no Kotlin or Android dependency. This lets
us test the calculus independently and reuse it from other frontends later.

## Kotlin compiler compatibility

Kotlin's FIR compiler-plugin API is experimental, so the checker treats the FIR
surface as a versioned adapter. Shared inference code remains under
`compiler-plugin/src`, while the small incompatible seams live under
`src-kotlin-2.3` and `src-kotlin-2.4`. The selected adapter, Kotlin Gradle
plugin, and `org.jetbrains.kotlin` compiler dependencies are aligned through:

```shell
./gradlew build -PkotlinVersion=2.3.0
```

One build produces an artifact for one Kotlin compiler version. The eventual
published Gradle plugin will select a version-aligned compiler artifact; it
must not load a 2.4 FIR artifact into a 2.3 compiler process. CI builds and
tests every supported compiler line so adapter drift fails before publication.

Kotlin first resolves each expression's ordinary type and call target. The FIR
visitor then computes the effect component on the same resolved tree; effects
are logically paired with Kotlin types but do not replace Kotlin's type
representation. Per-expression effects are temporary, while reusable function
summaries are cached by resolved FIR symbol.

Kotlin syntax-to-effect rules live in `KotlinNetworkEffectVisitor.kt`. The
surrounding inference pass is responsible only for interprocedural caching,
recursion boundaries, contracts, and diagnostics. The frontend computes effects
directly; it does not build an intermediate network-program tree. IR is reserved
for transformations such as the future bounded-scope semaphore rewrite.

## Annotation discipline

Annotations are required only where inference cannot see enough:

1. `@NetworkDownload(maxBytes, completeTimeoutMillis)` on primitive network
   operations or library adapters.
2. `@BandwidthEffect` download-effect lists on opaque functions, higher-order
   inputs, and opaque returned function types. Entries retain
   `(rMaxBytesPerSecond, nMax, selfBound, lifetime)`, where a zero `selfBound`
   means unspecified. `(rMaxBytesPerSecond, nMax)` remains one-entry shorthand.
3. `@BoundedScope(k)` on a `CoroutineScope` property when the compiler will
   enforce the stated launch bound.
4. `@BandwidthAlternative` on a whole `try/catch` expression when the
   programmer asserts that its network branches are comparable alternatives.

The MVP has no priorities and no download identifiers.

## Bounded scopes

The intended source:

```kotlin
@BoundedScope(k = 4)
val downloadScope = viewModelScope

downloadScope.launch {
    repository.download(url)
}
```

will later target the equivalent of:

```kotlin
val downloadScopeGate = BoundedScopeGate(permits = 4)

downloadScope.launch {
    downloadScopeGate.withPermit {
        repository.download(url)
    }
}
```

The static core models this as bounded replication of the entire launched
body's effect, rather than replacing every body with `{(R, k)}`. If one body
can internally run two downloads in parallel, four concurrent bodies may
expose eight downloads.

Before enabling the rewrite, the Kotlin frontend must reject or conservatively
handle scope aliases and escapes, reassignments, nested launches that can wait
while holding a permit, and launches whose network work escapes the gated body.

## Recovery and branches

Ordinary Kotlin conditionals and unannotated `try/catch` expressions initially
use conservative choice: their branch effects are sequentially joined.

`@BandwidthAlternative` is intentionally not implemented as "take the catch
effect" or "take the smaller effect." Either rule would incorrectly give:

```kotlin
try { downloadImage() }
catch (_: TimeoutException) { showError() }
```

a zero bandwidth requirement. The checker will only exploit the annotation
after we define a compositional recovery judgment that distinguishes a true
smaller representation from an error-only fallback.

Path sensitivity should carry rate predicates as refinements rather than label
one syntactic branch globally "low bandwidth." Nested checks such as
`bandwidth < 2` and `bandwidth < 5` require interval-aware composition.

## Milestones

### M0 - Repository and exact core

- Build with the current stable Kotlin compiler line.
- Implement exact rational rates, normalization, coverage, sequential join,
  parallel composition, bounded replication, and `ReqBW`.
- Add unit tests directly from paper examples.

### M1 - Annotation and plugin diagnostics

- [x] Validate annotation placement and constant arguments in FIR.
- [x] Resolve primitive operations and opaque latent effects by symbol.
- [x] Emit actionable source-located FIR diagnostics.
- Add Kotlin compiler test-data fixtures.

### M2 - Inference for sequential Kotlin

- [x] Infer calls, `let`/statement sequence, functions, ordinary branches, and
  `try/catch` directly from resolved Kotlin FIR.
- [x] Structure inference as a return-valued Kotlin FIR visitor so each
  additional language construct has an explicit extension point.
- [x] Infer visible effects and use summaries across opaque boundaries.
- [x] Check visible function and callback bodies against declared contracts.
- [x] Propagate latent effects through local storage, aliases, captures,
  mutable branch assignments, and higher-order function returns.
- [x] Require and check latent contracts on opaque higher-order inputs and
  returned function types.
- [x] Cache per-function summaries, reject unsupported recursion, and infer
  general loops by keeping completing iterations sequential while applying
  unknown repetition to bounded escaping work.
- [x] Run annotation validation, effect inference, and diagnostics in the FIR
  frontend without an analysis-only IR pass.

### M3 - Structured coroutine concurrency

- [x] Recognize visible `coroutineScope` blocks and preserve sequential work.
- [x] Compose `async` and `launch` bodies with the remaining scope in parallel.
- [x] Use direct `await` and `join` calls on local child handles to shorten
  conservatively inferred overlap windows.
- [x] Treat `withContext` as a structured scope and inline
  `awaitAll(async { ... }, ...)` as source-ordered child starts followed by one
  synchronization point.
- [x] Model eager `map { async { ... } }` collections as unknown structured
  fan-out requiring self-bounded downloads. Track a local collection handle so
  direct or stored `awaitAll()` ends its overlap window.
- [x] Model trusted `forEach` and AndroidX `traceAsync` callbacks as sequential
  invocation; unknown higher-order library calls still require contracts.
- Keep aliased, reassigned, stored, or escaped child handles live until scope
  completion unless ownership can be proved.
- [x] Distinguish structured completion from escaped jobs. An unqualified
  `launch`/`async` in the current `coroutineScope`/`withContext` is a direct
  child only when its context is absent or a recognized dispatcher. Explicit
  scope receivers and job-replacing or unknown contexts are tagged
  `MAY_OUTLIVE_CALL`. Builder blocks may be visible lambdas or latent callback
  values; unresolved callback effects are rejected. Lifetime-aware sequential
  composition keeps escaping work parallel with later work without
  rematerializing it at every call boundary. Escaping network work requires
  bounded clients.
- Compare inferred results against hand-written core fixtures.

### M4 - Enforced bounded network scopes

- Check ownership/alias restrictions for `@BoundedScope`.
- Rewrite supported launches through `BoundedScopeGate`.
- Test cancellation, exceptions, nesting, and Android lifecycle scopes.

### M5 - Rate-sensitive control flow and recovery

- Introduce bandwidth interval refinements for explicit rate checks.
- Formalize `@BandwidthAlternative` and nested `try/catch` composition.
- Keep error-message catches conservative unless they establish successful
  comparable network work.

### M6 - Android integration and evaluation

- Build and test version-aligned FIR adapters for Kotlin 2.3 and 2.4 while
  keeping the effect domain and annotation model shared.
- Keep a compiler fixture matching Now in Android's
  `withContext`/`awaitAll`/`chunked().forEach` sync structure.
- Provide an Android/Gradle sample with OkHttp or Retrofit boundary adapters.
- Analyze representative open-source applications.
- Measure annotation count, unsupported constructs, precision, build overhead,
  and discovered timeout/bandwidth mismatches.
