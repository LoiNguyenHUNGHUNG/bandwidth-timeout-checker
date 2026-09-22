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
  - repeat -> completing work stays sequential; escaping work uses self bounds
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
`src-kotlin-2.2`, `src-kotlin-2.3`, and `src-kotlin-2.4`. The selected adapter,
Kotlin Gradle plugin, and `org.jetbrains.kotlin` compiler dependencies are
aligned through:

```shell
./gradlew build -PkotlinVersion=2.2.20
```

One build produces an artifact for one Kotlin compiler version. The eventual
published Gradle plugin will select a version-aligned compiler artifact; it
must not load an artifact built for one FIR line into another. CI builds and
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
2. `@EntryPoint` on a framework-invoked application root that ordinary source
   code may never call. Every marked root contributes one invocation to the
   virtual application root.
3. `@BandwidthEffect` download-effect lists and named effect variables on
   opaque functions, higher-order inputs, and opaque returned function types.
   Entries retain
   `(rMaxBytesPerSecond, nMax, selfBound, lifetime)`, where a zero `selfBound`
   denotes the default bound infinity. A positive value is a trusted contract
   that runtime configuration establishes a finite limit.
   `(rMaxBytesPerSecond, nMax)` remains one-entry shorthand.
4. `@BoundedClient(k)` on a client whose runtime configuration establishes the
   same concurrency limit.
5. `@BoundedScope(k)` on a `CoroutineScope` property when the compiler will
   enforce the stated launch bound.
6. `@BandwidthAlternative` on a whole `try/catch` expression when the
   programmer asserts that its network branches are comparable alternatives.

The MVP has no priorities and no download identifiers.

## Unknown repetition

The core exposes one `repeat(Phi)` rule. It splits `Phi` into work that completes
with one invocation and work that escapes it. Completing work keeps its local
peak effect. Escaping work may overlap across an unknown number of later
invocations, so every escaping download must carry a self bound before unknown
replication is applied. Usage determines this obligation: ordinary work outside
`repeat` does not require a self bound.

The Kotlin frontend only identifies repetition boundaries and the repeated
body. General loops, `forEach`, retained button callbacks, and lazy scrolling or
item callbacks all lower through the same rule. Retained callbacks tag the
result as potentially outliving the surrounding call after repetition is
computed. Adding another framework construct therefore extends only frontend
recognition, not the quantitative algebra.

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

## Explicit semaphore gates

The FIR frontend recognizes the structured
`kotlinx.coroutines.sync.Semaphore.withPermit` form when its receiver is an
immutable top-level property initialized with a compiler-known positive
capacity:

```kotlin
private const val MAX_HANDLERS = 4
private val handlerSlots = Semaphore(MAX_HANDLERS)

suspend fun handler() = handlerSlots.withPermit {
    download()
}
```

For one call to `handler`, the effect remains one call to `download`; the
semaphore does not manufacture concurrency. Instead, the frontend records that
at most four invocations of the entire completing block may be active. When an
enclosing entry point, loop, or callback creates repeated escaping invocations,
the ordinary repetition rule consumes that bound. Internal fan-out is
preserved: if one invocation starts two parallel downloads, four active permits
may expose eight downloads.

Operationally, a server continues accepting requests rather than stopping
after four total invocations:

```text
repeat(infinity) {
    spawn { acquire(handlerSlots); e; release(handlerSlots) }
}
```

For peak network demand, waiting handlers have no network effect. If the permit
is held until `e` completes, the protected projection is therefore:

```text
repeat(4) { spawn { e } }
```

More generally, `selfBound` defaults to infinity and the effective multiplicity
is computed after the ordinary effect rules:

```text
n_effective = min(n_standard, selfBound)
```

At a repeated-handler boundary, `n_standard` is infinity. A recognized finite
semaphore bound therefore makes the result finite; without one, the multiplier
remains infinity. The finite checker reports an error only when a non-empty
network effect has an infinite effective bandwidth requirement. An empty
handler remains valid.

## Application entry points

An `@EntryPoint` function denotes a framework-invoked application root `e`.
The virtual main makes otherwise unreachable roots live concurrently:

```text
spawn { EntryPoint1() }
spawn { EntryPoint2() }
...
```

The FIR checker infers one invocation of each marked function. Once all source
functions have been checked, a non-transforming IR hook builds the virtual
application root:

```text
Application = EntryPoint1 || EntryPoint2 || ... || EntryPointN
```

This hook only reports the combined effect; it does not generate a Kotlin
`main` or modify executable code.

Ktor routing APIs provide a separate callback boundary. A `routing` call
invokes its configuration lambda exactly once. A `get` call retains its handler
and may run arbitrarily many handler invocations concurrently:

```text
routing { f } = f
get { f } = repeat(infinity) { spawn { f } }
```

The callback rule first promotes the complete handler body to an escaping
framework spawn and then applies unknown repetition. A finite self bound from a
persistent semaphore inside `f` therefore replaces infinity through the core
repetition rule. The first Ktor experiment requires a distinct statically
visible semaphore for every network handler. Shared gate identity and aliasing
remain outside this initial model.

A local semaphore is transparent because recreating it for each function
invocation establishes no shared bound. Dynamic capacities and mutable or
aliased semaphore properties are also transparent. Non-visible blocks and
manual `acquire`/`release` pairs remain outside the supported fragment. Network
work that escapes the block is no longer protected once `withPermit` releases
the permit, so it receives no semaphore bound; a surrounding repetition will
reject it unless another runtime mechanism supplies one.

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
- [x] Bind named callback effect variables and substitute them into
  higher-order call summaries, including an optional escaping lifetime.
- [x] Cache per-function summaries, reject unsupported recursion, and lower
  general loops through the core repetition rule.
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
- [x] Recognize a visible `Semaphore.withPermit` block on an immutable shared
  semaphore with constant capacity, preserving one invocation while carrying a
  whole-block bound to enclosing repetition. Escaping work receives no gate
  bound.
- [x] Keep visible `flow`, collection `asFlow`, and `flatMapMerge` pipelines
  latent until no-argument `collect()`. Require an explicit positive constant
  concurrency bound, apply it to completing inner-flow work, and route escaping
  transform and inner-flow work through the core repetition rule. Reject
  collector callbacks until their incompatible version-specific FIR shapes are
  normalized.
- [x] Lower `forEach` through the core repetition rule and model AndroidX
  `traceAsync` as one callback invocation; unknown higher-order library calls
  still require contracts.
- Keep aliased, reassigned, stored, or escaped child handles live until scope
  completion unless ownership can be proved.
- [x] Distinguish structured completion from escaped jobs. An unqualified
  `launch`/`async` in the current `coroutineScope`/`withContext` is a direct
  child only when its context is absent or a recognized dispatcher. Explicit
  scope receivers and job-replacing or unknown contexts are tagged
  `MAY_OUTLIVE_CALL`. Builder blocks may be visible lambdas or latent callback
  values; unresolved callback effects are rejected. Lifetime-aware sequential
  composition keeps escaping work parallel with later work without
  rematerializing it at every call boundary. One builder is one spawned child,
  so lifetime promotion does not itself apply unknown repetition. Escaping
  network work requires self bounds only when a loop, retained callback, or
  another repetition boundary can create an unknown number of children.
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

- Build and test version-aligned FIR adapters for Kotlin 2.2, 2.3, and 2.4 while
  keeping the effect domain and annotation model shared.
- Keep a compiler fixture matching Now in Android's
  `withContext`/`awaitAll`/`chunked().forEach` sync structure.
- Provide an Android/Gradle sample with OkHttp or Retrofit boundary adapters.
- Analyze representative open-source applications.
- Measure annotation count, unsupported constructs, precision, build overhead,
  and discovered timeout/bandwidth mismatches.
