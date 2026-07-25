package io.github.loinguyen.bandwidth.compiler

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CompilerPluginIntegrationTest {
    @Test
    fun `infers sequential and branch effects under a function contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 6_000, completeTimeoutMillis = 10_000)
            fun large() = Unit

            @NetworkDownload(maxBytes = 4_000, completeTimeoutMillis = 10_000)
            fun small() = Unit

            @BandwidthEffect(rMaxBytesPerSecond = 600, nMax = 1)
            fun load(useLarge: Boolean) {
                small()
                if (useLarge) large() else small()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(600, 1)}")
        result.assertOutputContains("ReqBW=600 bytes/s")
    }

    @Test
    fun `sequential inference preserves rate and concurrency correlation`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            fun fastSingle() = Unit

            @BandwidthEffect(rMaxBytesPerSecond = 100, nMax = 10)
            fun slowConcurrentBoundary() = Unit

            fun combined() {
                fastSingle()
                slowConcurrentBoundary()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for combined: {(1000, 1), (100, 10)}",
        )
        result.assertOutputContains("ReqBW=1000 bytes/s")
    }

    @Test
    fun `joins try and catch paths conservatively`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 8_000, completeTimeoutMillis = 10_000)
            fun large() = Unit

            @NetworkDownload(maxBytes = 2_000, completeTimeoutMillis = 10_000)
            fun small() = Unit

            @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1)
            fun recover() {
                try {
                    large()
                } catch (_: Exception) {
                    small()
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for recover: {(800, 1)}")
    }

    @Test
    fun `keeps ordinary coroutineScope statements sequential`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun first() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun second() = Unit

            suspend fun load() = coroutineScope {
                first()
                second()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 1)}")
        result.assertOutputContains("ReqBW=1000 bytes/s")
    }

    @Test
    fun `composes launched children with the remaining scope in parallel`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun first() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun second() = Unit

            suspend fun load() = coroutineScope {
                first()
                launch {
                    second()
                }
                second()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1000, 1), (500, 2)}",
        )
        result.assertOutputContains("ReqBW=1000 bytes/s")
    }

    @Test
    fun `composes assigned async children in parallel`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun large() = Unit

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            suspend fun small() = Unit

            suspend fun load() = coroutineScope {
                val largeResult = async { large() }
                val smallResult = async { small() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(900, 2)}",
        )
        result.assertOutputContains("ReqBW=1800 bytes/s")
    }

    @Test
    fun `join ends a launched child's overlap with later work`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 400, completeTimeoutMillis = 1_000)
            suspend fun childDownload() = Unit

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            suspend fun beforeJoin() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun afterJoin() = Unit

            suspend fun load() = coroutineScope {
                val job = launch { childDownload() }
                beforeJoin()
                job.join()
                afterJoin()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(900, 1), (400, 2)}",
        )
        result.assertOutputContains("ReqBW=900 bytes/s")
    }

    @Test
    fun `await ends only the awaited child's overlap`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            suspend fun firstChild() = Unit

            @NetworkDownload(maxBytes = 400, completeTimeoutMillis = 1_000)
            suspend fun secondChild() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun afterFirstAwait() = Unit

            suspend fun load() = coroutineScope {
                val first = async { firstChild() }
                val second = async { secondChild() }
                first.await()
                afterFirstAwait()
                second.await()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(900, 2)}",
        )
        result.assertOutputContains("ReqBW=1800 bytes/s")
    }

    @Test
    fun `keeps aliased child handles live conservatively`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            suspend fun childDownload() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun afterAwait() = Unit

            suspend fun load() = coroutineScope {
                val original = async { childDownload() }
                val alias = original
                alias.await()
                afterAwait()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(900, 2)}",
        )
        result.assertOutputContains("ReqBW=1800 bytes/s")
    }

    @Test
    fun `join and await create successive overlap phases`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            suspend fun launchedChild() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun asyncChild() = Unit

            @NetworkDownload(maxBytes = 400, completeTimeoutMillis = 1_000)
            suspend fun beforeJoin() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun betweenWaits() = Unit

            @NetworkDownload(maxBytes = 1_100, completeTimeoutMillis = 1_000)
            suspend fun afterAwait() = Unit

            suspend fun load() = coroutineScope {
                val job = launch { launchedChild() }
                val deferred = async { asyncChild() }
                beforeJoin()
                job.join()
                betweenWaits()
                deferred.await()
                afterAwait()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1100, 1), (900, 2), (500, 3)}",
        )
        result.assertOutputContains("ReqBW=1800 bytes/s")
    }

    @Test
    fun `await and join can synchronize children in reverse order`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun launchedChild() = Unit

            @NetworkDownload(maxBytes = 200, completeTimeoutMillis = 1_000)
            suspend fun asyncChild() = Unit

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun beforeAwait() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun betweenWaits() = Unit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun afterJoin() = Unit

            suspend fun load() = coroutineScope {
                val job = launch { launchedChild() }
                val deferred = async { asyncChild() }
                beforeAwait()
                deferred.await()
                betweenWaits()
                job.join()
                afterJoin()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1000, 1), (900, 2), (700, 3)}",
        )
        result.assertOutputContains("ReqBW=2100 bytes/s")
    }

    @Test
    fun `nested coroutine scopes honor inner await and outer join`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            suspend fun innerChild() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun beforeInnerAwait() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun afterInnerAwait() = Unit

            @NetworkDownload(maxBytes = 400, completeTimeoutMillis = 1_000)
            suspend fun outerSiblingWork() = Unit

            @NetworkDownload(maxBytes = 1_100, completeTimeoutMillis = 1_000)
            suspend fun afterOuterJoin() = Unit

            suspend fun load() = coroutineScope {
                val outer = launch {
                    val inner = async { innerChild() }
                    beforeInnerAwait()
                    inner.await()
                    afterInnerAwait()
                }
                outerSiblingWork()
                outer.join()
                afterOuterJoin()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1100, 1), (900, 2), (500, 3)}",
        )
        result.assertOutputContains("ReqBW=1800 bytes/s")
    }

    @Test
    fun `composes coroutine await phases across function calls`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            suspend fun innerChild() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun innerConcurrentWork() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun innerAfterAwait() = Unit

            suspend fun innerLoad() = coroutineScope {
                val inner = async { innerChild() }
                innerConcurrentWork()
                inner.await()
                innerAfterAwait()
            }

            @NetworkDownload(maxBytes = 400, completeTimeoutMillis = 1_000)
            suspend fun outerChild() = Unit

            @NetworkDownload(maxBytes = 1_100, completeTimeoutMillis = 1_000)
            suspend fun outerAfterAwait() = Unit

            suspend fun outerLoad() = coroutineScope {
                val outer = async { outerChild() }
                innerLoad()
                outer.await()
                outerAfterAwait()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for innerLoad: {(900, 1), (500, 2)}",
        )
        result.assertOutputContains(
            "Inferred bandwidth effect for outerLoad: {(1100, 1), (900, 2), (500, 3)}",
        )
        result.assertOutputContains("ReqBW=1800 bytes/s")
    }

    @Test
    fun `infers inline awaitAll children inside withContext`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.withContext

            interface SyncRepository {
                @BandwidthEffect(rMaxBytesPerSecond = 900, nMax = 1)
                suspend fun sync(): Boolean
            }

            @NetworkDownload(maxBytes = 1_100, completeTimeoutMillis = 1_000)
            suspend fun populateSearchIndex() = Unit

            suspend fun syncAll(
                topicRepository: SyncRepository,
                newsRepository: SyncRepository,
            ) = withContext(Dispatchers.IO) {
                val syncedSuccessfully = awaitAll(
                    async { topicRepository.sync() },
                    async { newsRepository.sync() },
                ).all { it }

                if (syncedSuccessfully) {
                    populateSearchIndex()
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for syncAll: {(1100, 1), (900, 2)}",
        )
        result.assertOutputContains("ReqBW=1800 bytes/s")
    }

    @Test
    fun `keeps chunked forEach network work sequential`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun downloadBatch(ids: List<String>) = Unit

            suspend fun syncChanged(ids: List<String>) {
                ids.chunked(40).forEach { chunkedIds ->
                    downloadBatch(chunkedIds)
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for syncChanged: {(700, 1)}",
        )
        result.assertOutputContains("ReqBW=700 bytes/s")
    }

    @Test
    fun `withContext preserves ordinary sequential work`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.withContext

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            suspend fun first() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun second() = Unit

            suspend fun load() = withContext(Dispatchers.IO) {
                first()
                second()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(800, 1)}",
        )
        result.assertOutputContains("ReqBW=800 bytes/s")
    }

    @Test
    fun `treats Android traceAsync as a sequential wrapper`() {
        val result = compile(
            """
            package androidx.tracing

            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            inline fun <T> traceAsync(
                methodName: String,
                cookie: Int,
                @BandwidthEffect(rMaxBytesPerSecond = 1_000, nMax = 1)
                block: () -> T,
            ): T = block()

            @NetworkDownload(maxBytes = 750, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun tracedSync() = traceAsync("Sync", 0) {
                download()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for androidx.tracing.tracedSync: {(750, 1)}",
        )
        result.assertOutputContains("ReqBW=750 bytes/s")
    }

    @Test
    fun `rejects a function contract that does not cover its inferred body`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 6_000, completeTimeoutMillis = 10_000)
            fun primitive() = Unit

            @BandwidthEffect(rMaxBytesPerSecond = 500, nMax = 1)
            fun load() {
                primitive()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred effect {(600, 1)} is not covered")
        result.assertOutputContains("Test.kt:")
    }

    @Test
    fun `charges the declared effect of an invoked higher-order parameter`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            @BandwidthEffect(rMaxBytesPerSecond = 700, nMax = 2)
            fun invokeNetwork(
                @BandwidthEffect(rMaxBytesPerSecond = 700, nMax = 2)
                callback: () -> Unit,
            ) {
                callback()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for invokeNetwork: {(700, 2)}")
        result.assertOutputContains("ReqBW=1400 bytes/s")
    }

    @Test
    fun `infers the latent effect of a stored lambda`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            fun caller() {
                val operation = {
                    primitive()
                }
                operation()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for caller: {(1000, 1)}")
    }

    @Test
    fun `tracks function references through local aliases`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            fun caller() {
                val operation = ::primitive
                val alias = operation
                alias()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for caller: {(800, 1)}")
    }

    @Test
    fun `tracks inferred latent effects captured by another lambda`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 650, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            fun caller() {
                val inner = {
                    primitive()
                }
                val outer = {
                    inner()
                }
                outer()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for caller: {(650, 1)}")
    }

    @Test
    fun `joins latent effects assigned on different branches`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 200, completeTimeoutMillis = 1_000)
            fun small() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            fun large() = Unit

            fun caller(useLarge: Boolean) {
                var operation: () -> Unit = ::small
                if (useLarge) {
                    operation = ::large
                }
                operation()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for caller: {(900, 1)}")
    }

    @Test
    fun `propagates latent effects returned by callback factories`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 750, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            fun makeFactory(): () -> () -> Unit = {
                {
                    primitive()
                }
            }

            fun caller() {
                val factory = makeFactory()
                val operation = factory()
                operation()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for caller: {(750, 1)}")
    }

    @Test
    fun `uses a latent effect contract on an opaque callback return`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            interface Boundary {
                fun callback():
                    @BandwidthEffect(rMaxBytesPerSecond = 900, nMax = 1) (() -> Unit)
            }

            fun caller(boundary: Boundary) {
                val operation = boundary.callback()
                operation()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for caller: {(900, 1)}")
    }

    @Test
    fun `checks a visible callback return against its latent contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            fun callback():
                @BandwidthEffect(rMaxBytesPerSecond = 500, nMax = 1) (() -> Unit) = {
                primitive()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred returned latent effect {(900, 1)} is not covered")
    }

    @Test
    fun `validates a latent effect annotation on a callback return type`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            interface Boundary {
                fun callback():
                    @BandwidthEffect(rMaxBytesPerSecond = 900, nMax = 0) (() -> Unit)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "@BandwidthEffect with a positive rMax must have nMax greater than zero",
        )
    }

    @Test
    fun `rejects invocation of an opaque callback return without a latent contract`() {
        val result = compile(
            """
            interface Boundary {
                fun callback(): () -> Unit
            }

            fun caller(boundary: Boundary) {
                val operation = boundary.callback()
                operation()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer the latent effect of an invoked function value")
    }

    @Test
    fun `requires a contract when a higher-order parameter is invoked`() {
        val result = compile(
            """
            fun invokeNetwork(callback: () -> Unit) {
                callback()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Higher-order parameter 'callback' is invoked without")
    }

    @Test
    fun `checks a visible callback against its higher-order parameter contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            fun invokeNetwork(
                @BandwidthEffect(rMaxBytesPerSecond = 500, nMax = 1)
                callback: () -> Unit,
            ) {
                callback()
            }

            fun caller() {
                invokeNetwork {
                    primitive()
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Higher-order argument effect {(600, 1)} is not covered")
    }

    @Test
    fun `does not silently discard an effectful external higher-order argument`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            fun repeated() {
                repeat(2) {
                    primitive()
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Effectful higher-order argument for parameter 'action'")
    }

    @Test
    fun `rejects unannotated recursion`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            fun recursive() {
                primitive()
                recursive()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer recursive network function recursive")
    }

    @Test
    fun `uses a declared FIR effect as a recursion boundary`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            @BandwidthEffect(rMaxBytesPerSecond = 1_000, nMax = 1)
            fun recursive(remaining: Int) {
                primitive()
                if (remaining > 0) {
                    recursive(remaining - 1)
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for recursive: {(1000, 1)}",
        )
    }

    @Test
    fun `rejects an effectful loop`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            fun repeated(count: Int) {
                var index = 0
                while (index < count) {
                    primitive()
                    index += 1
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer an effectful loop")
    }

    private fun compile(
        source: String,
        reportEffects: Boolean = false,
    ): CompilationResult {
        val directory: Path = Files.createTempDirectory("bandwidth-compiler-test")
        val sourceFile: Path = directory.resolve("Test.kt")
        val outputDirectory: Path = directory.resolve("classes")
        Files.createDirectories(outputDirectory)
        sourceFile.writeText(source.trimIndent())

        val classpath: String = requiredProperty("bandwidth.compiler.test.classpath")
            .split(File.pathSeparator)
            .filter { File(it).exists() }
            .joinToString(File.pathSeparator)
        val pluginJar: String = requiredProperty("bandwidth.compiler.plugin.jar")
        val javaExecutable: String = Path.of(
            System.getProperty("java.home"),
            "bin",
            "java",
        ).toString()
        val command: MutableList<String> = mutableListOf(
            javaExecutable,
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            classpath,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib",
            "-no-reflect",
            "-classpath",
            classpath,
            "-Xplugin=$pluginJar",
            "-d",
            outputDirectory.toString(),
        )
        if (reportEffects) {
            command += listOf(
                "-P",
                "plugin:io.github.loinguyen.bandwidth:reportEffects=true",
            )
        }
        command += sourceFile.toString()

        val process: Process = ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output: String = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode: Int = process.waitFor()
        directory.toFile().deleteRecursively()
        return CompilationResult(exitCode, output)
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Missing system property $name" }

    private data class CompilationResult(
        val exitCode: Int,
        val output: String,
    ) {
        fun assertOutputContains(expected: String) {
            assertContains(output.lowercase(), expected.lowercase())
        }
    }
}
