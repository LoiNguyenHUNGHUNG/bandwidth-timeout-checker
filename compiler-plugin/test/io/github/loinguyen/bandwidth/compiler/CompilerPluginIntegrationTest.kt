package io.github.loinguyen.bandwidth.compiler

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class CompilerPluginIntegrationTest {
    @Test
    fun `explicit invoke charges the stored callback effect`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            fun download() = Unit
            fun root() {
                val callback = { download() }
                callback.invoke()
            }
            """, reportEffects = true,
        )
        assertEquals(0, result.exitCode, result.output)
        result.assertFunctionBandwidth("root", 1000)
    }

    @Test
    fun `with invokes its callback once and retains its network work`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            fun download() = Unit
            fun root() { with("scope") { download() } }
            """, reportEffects = true,
        )
        assertEquals(0, result.exitCode, result.output)
        result.assertFunctionBandwidth("root", 1000)
    }

    @Test
    fun `infers Flow collector callbacks across compiler versions`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            suspend fun download(url: String) = Unit

            suspend fun load(urls: List<String>) {
                urls.asFlow().collect { url -> download(url) }
            }
            """,
        )

        assertEquals(0, result.exitCode, result.output)
    }

    @Test
    fun `keeps independent gate alternatives distinct before effect substitution`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.Handler
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private val firstSlots = Semaphore(3)
            private val secondSlots = Semaphore(3)

            @BandwidthVariable("Body")
            suspend fun chooseGate(
                first: Boolean,
                @BandwidthEffect("Body") task: suspend () -> Unit,
            ) {
                if (first) {
                    firstSlots.withPermit { task() }
                } else {
                    secondSlots.withPermit { task() }
                }
            }

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun high() = Unit

            fun register(callback: suspend () -> Unit) = Unit

            fun configure(first: Boolean) {
                register(@Handler { chooseGate(first) { high() } })
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for configure: {(100, 6)}")
        result.assertOutputContains("ReqBW=600 bytes/s")
    }

    @Test
    fun `keeps equal effects under independent handler gates distinct across branches`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.Handler
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private val firstSlots = Semaphore(3)
            private val secondSlots = Semaphore(3)

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun high() = Unit

            fun register(callback: suspend (Boolean) -> Unit) = Unit

            fun configure() {
                register(@Handler { first ->
                    if (first) {
                        firstSlots.withPermit { high() }
                    } else {
                        secondSlots.withPermit { high() }
                    }
                })
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for configure: {(100, 6)}")
        result.assertOutputContains("ReqBW=600 bytes/s")
    }

    @Test
    fun `does not extend a helper semaphore bound to unguarded handler work`() {
        val result = compileSources(
            sources = listOf(
                phasedHandlerFixture(),
                """
                import io.github.loinguyen.bandwidth.annotations.Handler

                fun register(callback: suspend () -> Unit) = Unit

                fun configure() {
                    register(@Handler {
                        gatedRequest()
                        low()
                    })
                }
                """,
            ),
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("concurrent repeated callback @Handler callback")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `a single handler permit preserves the effect of one invocation`() {
        val result = compileSources(
            sources = listOf(
                phasedHandlerFixture(permits = 1),
                """
                import io.github.loinguyen.bandwidth.annotations.Handler

                fun register(callback: suspend () -> Unit) = Unit

                fun configure() {
                    register(@Handler { gatedRequest() })
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for configure: {(100, 1), (1, 10)}")
        result.assertOutputContains("ReqBW=100 bytes/s")
    }

    @Test
    fun `preserves phases in a semaphore bounded Ktor handler`() {
        val result = compileSources(
            sources = listOf(
                ktorRoutingStub,
                phasedHandlerFixture(),
                """
                import io.github.loinguyen.bandwidth.annotations.EntryPoint
                import io.ktor.server.routing.Application
                import io.ktor.server.routing.get
                import io.ktor.server.routing.routing

                @EntryPoint
                fun Application.module() {
                    routing {
                        get("/phased") { gatedRequest() }
                    }
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(100, 21), (1, 30)}",
        )
        result.assertOutputContains("ReqBW=2100 bytes/s")
    }

    @Test
    fun `preserves phases in a semaphore bounded declaration handler`() {
        val result = compileSources(
            sources = listOf(
                phasedHandlerFixture(),
                """
                import io.github.loinguyen.bandwidth.annotations.Handler

                @Handler
                suspend fun frameworkCallback() {
                    gatedRequest()
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(100, 21), (1, 30)}",
        )
        result.assertOutputContains(
            "NAMED_HANDLER_EFFECT function=frameworkCallback " +
                "body={(100, 1), (1, 10)} repeated={(100, 21), (1, 30)}",
        )
        result.assertOutputContains("ReqBW=2100 bytes/s")
    }

    @Test
    fun `preserves phases when repeating a handler through a semaphore gated helper`() {
        val result = compileSources(
            sources = listOf(
                phasedHandlerFixture(),
                """
                import io.github.loinguyen.bandwidth.annotations.Handler

                fun register(callback: suspend () -> Unit) = Unit

                fun configure() {
                    register(@Handler { gatedRequest() })
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for gatedRequest: {(100, 1), (1, 10)}")
        result.assertOutputContains("Inferred bandwidth effect for configure: {(100, 21), (1, 30)}")
        result.assertOutputContains("ReqBW=2100 bytes/s")
    }

    @Test
    fun `accepts empty contract on pure recursion`() {
        val result = compile("""
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            @BandwidthEffect()
            fun recursive(remaining: Int) {
                if (remaining > 0) recursive(remaining - 1)
            }
        """)
        assertEquals(0, result.exitCode, result.output)
    }

    @Test
    fun `rejects recursion contract narrower than its body`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            @NetworkDownload(maxBytes = 2000, completeTimeoutMillis = 1000)
            fun primitive() = Unit
            @BandwidthEffect(rMaxBytesPerSecond = 1000, nMax = 1)
            fun recursive(remaining: Int) {
                primitive()
                if (remaining > 0) recursive(remaining - 1)
            }
            """,
        )
        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("not covered by @BandwidthEffect contract")
    }

    @Test
    fun `awaited map separates completing and escaping lifetimes`() {
        val result = compile("""
            import io.github.loinguyen.bandwidth.annotations.*
            import kotlinx.coroutines.*
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit
            @BandwidthEffect(downloads = [BandwidthDownload(
                rMaxBytesPerSecond = 1000, nMax = 1,
                mayOutliveCall = true, selfBound = 3,
            )])
            fun startBackground() = Unit
            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1000)
            fun childDownload() = Unit
            @NetworkDownload(maxBytes = 2000, completeTimeoutMillis = 1000)
            fun after() = Unit
            suspend fun load(values: List<Int>) = coroutineScope {
                val slots = Semaphore(3)
                values.map {
                    async { slots.withPermit { childDownload(); startBackground() } }
                }.awaitAll()
                after()
            }
        """, reportEffects = true)
        assertEquals(0, result.exitCode, result.output)
        val match = Regex(
            "Inferred bandwidth effect for load: .*?ReqBW=(\\d+) bytes/s",
            RegexOption.IGNORE_CASE,
        )
            .find(result.output)
        val bound = match?.groupValues?.get(1)?.toLong()
        // Three retained jobs still overlap `after`, requiring at least8000.
        // The independent-maxima simplification may retain all six bodies,
        // but neither model needs more than6 *2000 for this example.
        assertEquals(true, bound != null && bound in 8000L..12000L, result.output)
    }

    @Test
    fun `awaited map rejects unbounded escaping work`() {
        val result = compile("""
            import io.github.loinguyen.bandwidth.annotations.*
            import kotlinx.coroutines.*
            @BandwidthEffect(downloads = [BandwidthDownload(
                rMaxBytesPerSecond = 1000, nMax = 1, mayOutliveCall = true,
            )])
            fun startBackground() = Unit
            suspend fun load(values: List<Int>) = coroutineScope {
                values.map { async { startBackground() } }.awaitAll()
            }
        """)
        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("network work that may outlive that child without a self bound")
    }

    @Test
    fun `awaited map keeps escaping work beyond local permit`() {
        val result = compile("""
            import io.github.loinguyen.bandwidth.annotations.*
            import kotlinx.coroutines.*
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit
            @BandwidthEffect(downloads = [BandwidthDownload(
                rMaxBytesPerSecond = 1000, nMax = 1,
                mayOutliveCall = true, selfBound = 3,
            )])
            fun startBackground() = Unit
            @NetworkDownload(maxBytes = 2000, completeTimeoutMillis = 1000)
            fun after() = Unit
            suspend fun load(values: List<Int>) = coroutineScope {
                val slots = Semaphore(3)
                values.map { async { slots.withPermit { startBackground() } } }.awaitAll()
                after()
            }
        """, reportEffects = true)
        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("(2000, 4)")
        result.assertOutputContains("ReqBW=8000 bytes/s")
    }

    @Test
    fun `awaited map keeps bounded escaping work active`() {
        val result = compile("""
            import io.github.loinguyen.bandwidth.annotations.*
            import kotlinx.coroutines.*
            @BandwidthEffect(downloads = [BandwidthDownload(
                rMaxBytesPerSecond = 1000, nMax = 1,
                mayOutliveCall = true, selfBound = 3,
            )])
            fun startBackground() = Unit
            @NetworkDownload(maxBytes = 2000, completeTimeoutMillis = 1000)
            fun after() = Unit
            suspend fun load(values: List<Int>) = coroutineScope {
                values.map { async { startBackground() } }.awaitAll()
                after()
            }
        """, reportEffects = true)
        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("(2000, 4)")
        result.assertOutputContains("ReqBW=8000 bytes/s")
    }

    @Test
    fun `nested named contract still checked module wide`() {
        val result = compile("""
            import io.github.loinguyen.bandwidth.annotations.*
            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            fun download() = Unit
            fun root() {
                @BandwidthEffect()
                fun nested() { download() }
            }
        """)
        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("not covered by @BandwidthEffect contract")
    }

    @Test
    fun `nested named invocation activates its effect`() {
        val result = compile("""
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            fun download() = Unit
            fun root() {
                fun nested() { download() }
                nested()
            }
        """, reportEffects = true)
        assertEquals(0, result.exitCode, result.output)
        result.assertFunctionBandwidth("root", 1000)
    }

    @Test
    fun `nested named declaration stays latent`() {
        val result = compile("""
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            fun download() = Unit
            fun root() {
                fun nested() { download() }
            }
        """, reportEffects = true)
        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("RAW_FUNCTION_EFFECT function=root eager={}")
    }

    @Test
    fun `generic class normalization retains real overriding implementation`() {
        val result = compile("""
            import io.github.loinguyen.bandwidth.annotations.*
            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            fun small(): Int = 1
            @NetworkDownload(maxBytes = 2000, completeTimeoutMillis = 1000)
            fun large(): Int = 1
            open class Base<T> { open fun run(): Int = small() }
            class Derived : Base<Int>() { override fun run(): Int = large() }
            fun root() = Derived().run()
        """, reportEffects = true)
        assertEquals(0, result.exitCode, result.output)
        result.assertFunctionBandwidth("root", 2000)
    }

    @Test
    fun `generic class inferred retained callback survives call`() {
        val result = compile("""
            import io.github.loinguyen.bandwidth.annotations.*
            import kotlinx.coroutines.*
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit
            val slots = Semaphore(3)
            class Cache<T>(val scope: CoroutineScope) {
                @BandwidthVariable("E")
                fun submit(block: @BandwidthEffect("E") suspend () -> T) {
                    scope.launch { slots.withPermit { block() } }
                }
            }
            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            fun download(): Int = 1
            @NetworkDownload(maxBytes = 2000, completeTimeoutMillis = 1000)
            fun after() = Unit
            fun root(scope: CoroutineScope) {
                Cache<Int>(scope).submit { download() }
                after()
            }
        """, reportEffects = true)
        assertEquals(0, result.exitCode, result.output)
        result.assertFunctionBandwidth("root", 4000)
    }

    @Test
    fun `generic class inferred callback effect reaches caller`() {
        val result = compile("""
            import io.github.loinguyen.bandwidth.annotations.*
            class Box<T> {
                @BandwidthVariable("E")
                fun run(block: @BandwidthEffect("E") () -> T): T = block()
            }
            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            fun download(): Int = 1
            fun root() = Box<Int>().run { download() }
        """, reportEffects = true)
        assertEquals(0, result.exitCode, result.output)
        result.assertFunctionBandwidth("root", 1000)
    }

    @Test
    fun `rejects invalid or incomplete bounds instead of treating them as untimed`() {
        val invalidAnnotations = listOf(
            "@NetworkDownload(maxBytes = 100, completeTimeoutMillis = 0)",
            "@NetworkDownload(maxBytes = 100, completeTimeoutMillis = -1)",
            "@NetworkDownload(maxBytes = -1, completeTimeoutMillis = 1_000)",
            "@NetworkDownload(maxBytes = -2, completeTimeoutMillis = -2)",
            "@NetworkDownload(maxBytes = 100)",
            "@NetworkDownload(completeTimeoutMillis = 1_000)",
        )
        invalidAnnotations.forEach { annotation ->
            val result = compile(
                """
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload

                $annotation
                suspend fun download() = Unit
                """,
            )

            assertNotEquals(0, result.exitCode, "$annotation\n${result.output}")
            result.assertOutputContains("@NetworkDownload")
            result.assertOutputContains("must be a")
        }
    }

    @Test
    fun `keeps sequential untimed and timed downloads at concurrency one`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload()
            suspend fun untimed() = Unit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun timed() = Unit

            suspend fun load() {
                untimed()
                timed()
                untimed()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 1)}")
        result.assertOutputContains("ReqBW=1000 bytes/s")
    }

    @Test
    fun `counts untimed downloads competing with a timed primitive`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import io.github.loinguyen.bandwidth.annotations.None
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = None, completeTimeoutMillis = None)
            suspend fun untimed() = Unit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun timed() = Unit

            suspend fun load() = coroutineScope {
                launch { untimed() }
                launch { timed() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 2)}")
        result.assertOutputContains("ReqBW=2000 bytes/s")
    }

    @Test
    fun `reports an untimed primitive as one competing download`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import io.github.loinguyen.bandwidth.annotations.None

            @NetworkDownload(maxBytes = None, completeTimeoutMillis = None)
            suspend fun untimed() = Unit

            @NetworkDownload()
            suspend fun defaultUntimed() = Unit

            suspend fun load() = untimed()
            suspend fun loadDefault() = defaultUntimed()
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(0, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for loadDefault: {(0, 1)}")
        result.assertOutputContains("ReqBW=0 bytes/s")
    }

    @Test
    fun `keeps inferred structured concurrency with a bounded client`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @BoundedClient(k = 2)
            val client = NetworkClient()

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            suspend fun load() = coroutineScope {
                launch { client.download() }
                launch { client.download() }
                launch { client.download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 3)}")
        result.assertOutputContains("ReqBW=3000 bytes/s")
    }

    @Test
    fun `uses a bounded client parameter as a client type contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            suspend fun load(@BoundedClient(k = 3) client: NetworkClient) = coroutineScope {
                launch { client.download() }
                launch { client.download() }
                launch { client.download() }
                launch { client.download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(600, 4)}")
        result.assertOutputContains("ReqBW=2400 bytes/s")
    }

    @Test
    fun `does not replicate a single external launch to the client bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            fun onDownloadClicked(
                scope: CoroutineScope,
                @BoundedClient(k = 3) client: NetworkClient,
            ) {
                scope.launch { client.download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for onDownloadClicked: {(600, 1)}",
        )
        result.assertOutputContains("ReqBW=600 bytes/s")
    }

    @Test
    fun `allows a single escaping launch without a bounded client`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            fun onDownloadClicked(scope: CoroutineScope, client: NetworkClient) {
                scope.launch { client.download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for onDownloadClicked: {(600, 1)}",
        )
        result.assertOutputContains("ReqBW=600 bytes/s")
    }

    @Test
    fun `overlaps two sequential external launches`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.first() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.second() = Unit

            fun load(
                scope: CoroutineScope,
                firstClient: NetworkClient,
                secondClient: NetworkClient,
            ) {
                scope.launch { firstClient.first() }
                scope.launch { secondClient.second() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 2)}")
        result.assertOutputContains("ReqBW=2000 bytes/s")
    }

    @Test
    fun `keeps an explicit external scope long lived inside coroutine scope`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            suspend fun load(
                externalScope: CoroutineScope,
                @BoundedClient(k = 2) client: NetworkClient,
            ) = coroutineScope {
                externalScope.launch { client.download() }
                client.download()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 2)}")
        result.assertOutputContains("ReqBW=2000 bytes/s")
    }

    @Test
    fun `propagates escaping work through a function summary`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 750, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            fun spawn(
                scope: CoroutineScope,
                @BoundedClient(k = 4) client: NetworkClient,
            ) {
                scope.launch { client.download() }
            }

            suspend fun load(
                scope: CoroutineScope,
                @BoundedClient(k = 4) client: NetworkClient,
            ) {
                spawn(scope, client)
                client.download()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(750, 2)}")
        result.assertOutputContains("ReqBW=1500 bytes/s")
    }

    @Test
    fun `resolves an indirect structured launch block`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            suspend fun load(client: NetworkClient) {
                val task: suspend CoroutineScope.() -> Unit = { client.download() }
                coroutineScope { launch(block = task) }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 1)}")
    }

    @Test
    fun `resolves an indirect structured async block`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.async
            import kotlinx.coroutines.coroutineScope

            class NetworkClient
            class Result

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            suspend fun load(client: NetworkClient) = coroutineScope {
                val task: suspend CoroutineScope.() -> Result = {
                    client.download()
                    Result()
                }
                val deferred = async(block = task)
                deferred.await()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(800, 1)}")
    }

    @Test
    fun `allows an indirect external launch block without a self bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            fun load(scope: CoroutineScope, client: NetworkClient) {
                val task: suspend CoroutineScope.() -> Unit = { client.download() }
                scope.launch(block = task)
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(600, 1)}")
    }

    @Test
    fun `does not replicate an indirect external launch to the client bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            fun load(
                scope: CoroutineScope,
                @BoundedClient(k = 4) client: NetworkClient,
            ) {
                val task: suspend CoroutineScope.() -> Unit = { client.download() }
                scope.launch(block = task)
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(600, 1)}")
    }

    @Test
    fun `treats an unannotated indirect coroutine block as non-network`() {
        val result = compile(
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            suspend fun load(task: suspend CoroutineScope.() -> Unit) = coroutineScope {
                launch(block = task)
            }
            """,
        )

        assertEquals(0, result.exitCode, result.output)
    }

    @Test
    fun `treats an explicit Job context as escaping`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.Job
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.fast() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.slow() = Unit

            suspend fun load(
                @BoundedClient(k = 4) fastClient: NetworkClient,
                slowClient: NetworkClient,
            ) {
                coroutineScope { launch(Job()) { fastClient.fast() } }
                slowClient.slow()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 2)}")
        result.assertOutputContains("ReqBW=2000 bytes/s")
    }

    @Test
    fun `treats job replacing coroutine contexts as escaping`() {
        val contexts = listOf(
            "Job()",
            "SupervisorJob()",
            "NonCancellable",
            "context",
            "Dispatchers.IO + Job()",
        )
        contexts.forEach { coroutineContext ->
            val result = compile(
                """
                import io.github.loinguyen.bandwidth.annotations.BoundedClient
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import kotlin.coroutines.CoroutineContext
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.Job
                import kotlinx.coroutines.NonCancellable
                import kotlinx.coroutines.SupervisorJob
                import kotlinx.coroutines.coroutineScope
                import kotlinx.coroutines.launch

                class NetworkClient

                @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
                suspend fun NetworkClient.download() = Unit

                suspend fun load(
                    context: CoroutineContext,
                    @BoundedClient(k = 4) client: NetworkClient,
                ) = coroutineScope {
                    launch($coroutineContext) { client.download() }
                }
                """,
                reportEffects = true,
            )

            assertEquals(0, result.exitCode, "$coroutineContext\n${result.output}")
            result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
        }
    }

    @Test
    fun `keeps a known dispatcher context structured`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            suspend fun load(client: NetworkClient) = coroutineScope {
                launch(Dispatchers.IO) { client.download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
    }

    @Test
    fun `resolves callable reference and factory coroutine blocks`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 650, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            val client = NetworkClient()

            suspend fun CoroutineScope.referencedTask() {
                client.download()
            }

            fun makeTask(client: NetworkClient): suspend CoroutineScope.() -> Unit = {
                client.download()
            }

            suspend fun loadByReference() = coroutineScope {
                val task: suspend CoroutineScope.() -> Unit = CoroutineScope::referencedTask
                launch(block = task)
            }

            suspend fun loadByFactory(client: NetworkClient) = coroutineScope {
                val task = makeTask(client)
                launch(block = task)
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for loadByReference: {(650, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for loadByFactory: {(650, 1)}")
    }

    @Test
    fun `resolves an annotated coroutine block parameter`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            suspend fun load(
                @BandwidthEffect(rMaxBytesPerSecond = 900, nMax = 1)
                task: suspend CoroutineScope.() -> Unit,
            ) = coroutineScope {
                launch(block = task)
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(900, 1)}")
    }

    @Test
    fun `allows one Job replacing context without a self bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.Job
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            suspend fun load(client: NetworkClient) = coroutineScope {
                launch(Job()) { client.download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
    }

    @Test
    fun `propagates nested external work beyond a structured child`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.fast() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.slow() = Unit

            suspend fun load(
                externalScope: CoroutineScope,
                @BoundedClient(k = 4) fastClient: NetworkClient,
                slowClient: NetworkClient,
            ) {
                coroutineScope {
                    launch {
                        externalScope.launch { fastClient.fast() }
                    }
                }
                slowClient.slow()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 2)}")
    }

    @Test
    fun `uses self bound for forEach launches in a view model scope`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String) = Unit

            fun loadImages(
                urls: List<String>,
                viewModelScope: CoroutineScope,
                @BoundedClient(k = 4) imageClient: NetworkClient,
            ) {
                urls.forEach { url ->
                    viewModelScope.launch { imageClient.download(url) }
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for loadImages: {(900, 4)}",
        )
        result.assertOutputContains("ReqBW=3600 bytes/s")
    }

    @Test
    fun `lowers forEach to the core repetition rule`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthDownload
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            interface ImageLibrary {
                @BandwidthEffect(
                    downloads = [
                        BandwidthDownload(
                            rMaxBytesPerSecond = 400,
                            nMax = 1,
                            mayOutliveCall = true,
                            selfBound = 2,
                        ),
                    ],
                )
                fun start(url: String)
            }

            fun loadImages(urls: List<String>, library: ImageLibrary) {
                urls.forEach { url -> library.start(url) }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for loadImages: {(400, 2)}")
        result.assertOutputContains("ReqBW=800 bytes/s")
    }

    @Test
    fun `rejects unbounded escaping work in forEach`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthDownload
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            interface ImageLibrary {
                @BandwidthEffect(
                    downloads = [
                        BandwidthDownload(
                            rMaxBytesPerSecond = 400,
                            nMax = 1,
                            mayOutliveCall = true,
                        ),
                    ],
                )
                fun start(url: String)
            }

            fun loadImages(urls: List<String>, library: ImageLibrary) {
                urls.forEach { url -> library.start(url) }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work in repeated callback")
        result.assertOutputContains(
            "Use a self bound for every download",
        )
    }

    @Test
    fun `keeps completing work in collection map sequential`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            fun download(id: String) = Unit

            fun load(ids: List<String>) = ids.map { id ->
                download(id)
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
    }

    @Test
    fun `rejects unbounded escaping work in collection map`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthDownload
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            interface ImageLibrary {
                @BandwidthEffect(
                    downloads = [
                        BandwidthDownload(
                            rMaxBytesPerSecond = 400,
                            nMax = 1,
                            mayOutliveCall = true,
                        ),
                    ],
                )
                fun start(id: String)
            }

            fun load(ids: List<String>, library: ImageLibrary) = ids.map { id ->
                library.start(id)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work in repeated callback")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `invokes kotlin io use callback once`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import java.io.ByteArrayInputStream

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            fun download() = Unit

            fun load() = ByteArrayInputStream(byteArrayOf()).use {
                download()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(600, 1)}")
    }

    @Test
    fun `keeps completing work in mapNotNull sequential`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 650, completeTimeoutMillis = 1_000)
            fun download(id: String) = Unit

            fun load(ids: List<String>) = ids.mapNotNull { id ->
                download(id)
                id.takeIf { it.isNotEmpty() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(650, 1)}")
    }

    @Test
    fun `invokes runBlocking body once`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.runBlocking

            @NetworkDownload(maxBytes = 550, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            fun load() = runBlocking {
                download()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(550, 1)}")
    }

    @Test
    fun `substitutes a polymorphic callback effect`() {
        val result = compile(
            """
            package io.beatmaps.util

            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @BandwidthVariable("E")
            fun <T, R> handleMultipart(
                parts: List<T>,
                @BandwidthEffect("E") cb: (T) -> R,
            ) = parts.map(cb)

            @NetworkDownload(maxBytes = 450, completeTimeoutMillis = 1_000)
            fun download(part: String) = Unit

            fun load(parts: List<String>) = handleMultipart(parts) { part ->
                download(part)
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for io.beatmaps.util.load: {(450, 1)}",
        )
    }

    @Test
    fun `forwards a polymorphic effect through nested wrappers`() {
        val result = compile(
            """
            package io.beatmaps.util

            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @BandwidthVariable("E")
            fun <T> requireCaptcha(
                @BandwidthEffect("E") block: () -> T,
            ): T = block()

            @BandwidthVariable("E")
            fun <T> captchaIfPresent(
                @BandwidthEffect("E") block: () -> T,
            ): T = requireCaptcha(block)

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            fun download() = Unit

            fun load() = captchaIfPresent {
                download()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for io.beatmaps.util.load: {(500, 1)}",
        )
    }

    @Test
    fun `preserves inferred work inside a visible polymorphic wrapper`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 425, completeTimeoutMillis = 1_000)
            fun prepare() = Unit

            @BandwidthVariable("Body")
            fun invokeAfterPreparing(
                @BandwidthEffect("Body") body: () -> Unit,
            ) {
                prepare()
                body()
            }

            fun load() = invokeAfterPreparing {}
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(425, 1)}")
    }

    @Test
    fun `uses polymorphism instead of an application specific callback model`() {
        val result = compile(
            """
            package io.beatmaps

            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @BandwidthVariable("PageEffect")
            fun genericPage(
                @BandwidthEffect("PageEffect") headerTemplate: () -> Unit,
            ) = headerTemplate()

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            fun download() = Unit

            fun load() = genericPage {
                download()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for io.beatmaps.load: {(300, 1)}")
    }

    @Test
    fun `partially evaluates parallel symbolic callback effects`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.runBlocking

            @BandwidthVariable("Left", "Right")
            suspend fun invokeInParallel(
                @BandwidthEffect("Left") left: suspend () -> Unit,
                @BandwidthEffect("Right") right: suspend () -> Unit,
            ) = coroutineScope {
                launch { left() }
                launch { right() }
            }

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            fun fast() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            fun slow() = Unit

            fun load() = runBlocking {
                invokeInParallel(
                    left = { fast() },
                    right = { slow() },
                )
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 2)}")
        result.assertOutputContains("ReqBW=2000 bytes/s")
    }

    @Test
    fun `partially evaluates repeated sequential uses of an effect variable`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @BandwidthVariable("Task")
            fun invokeTwice(
                @BandwidthEffect("Task") task: () -> Unit,
            ) {
                task()
                task()
            }

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            fun download() = Unit

            fun load() = invokeTwice { download() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
    }

    @Test
    fun `rejects an unbound polymorphic effect variable`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            fun invoke(@BandwidthEffect("Missing") callback: () -> Unit) = callback()
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Effect variable 'Missing' is not declared")
    }

    @Test
    fun `binds an omitted optional callback to the empty effect`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable

            @BandwidthVariable("Optional")
            fun invokeOptional(
                @BandwidthEffect("Optional") callback: (() -> Unit)? = null,
            ) {
                callback?.invoke()
            }

            fun load() = invokeOptional()
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Cannot instantiate polymorphic effect"))
    }

    @Test
    fun `infers a symbolic effect on a returned callback`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @BandwidthVariable("E")
            fun returnCallback(
                @BandwidthEffect("E") callback: () -> Unit,
            ): () -> Unit = callback

            @NetworkDownload(maxBytes = 625, completeTimeoutMillis = 1_000)
            fun download() = Unit

            fun load() {
                val callback = returnCallback { download() }
                callback()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(625, 1)}")
    }

    @Test
    fun `rejects duplicate quantified effect variables`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable

            @BandwidthVariable("E", "E")
            fun invoke(@BandwidthEffect("E") callback: () -> Unit) = callback()
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("@BandwidthVariable declares duplicate names: E")
    }

    @Test
    fun `uses a symbolic invocation contract on an opaque higher-order function`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            interface Installer {
                @BandwidthVariable("E")
                @BandwidthEffect("E")
                fun install(@BandwidthEffect("E") configure: () -> Unit)
            }

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            fun download() = Unit

            fun configure(installer: Installer) {
                installer.install { download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for configure: {(800, 1)}",
        )
    }

    @Test
    fun `rejects an undeclared symbolic invocation effect`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            @BandwidthEffect("Missing")
            fun install(callback: () -> Unit) = Unit
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Effect variable 'Missing' is not declared")
    }

    @Test
    fun `models an annotated retained handler through the repetition rule`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.Handler
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            class Client

            fun register(callback: () -> Unit) = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            fun Client.download() = Unit

            fun configure(@BoundedClient(k = 3) client: Client) {
                register(@Handler { client.download() })
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for configure: {(900, 3)}")
        result.assertOutputContains("ReqBW=2700 bytes/s")
    }

    @Test
    fun `rejects an annotated retained handler without a runtime bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.Handler
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            fun register(callback: () -> Unit) = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            fun download() = Unit

            fun configure() {
                register(@Handler { download() })
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("concurrent repeated callback @Handler callback")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `uses a handler semaphore as the repeated invocation bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.Handler
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            val handlerSlots = Semaphore(permits = 3)

            fun register(callback: suspend () -> Unit) = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            fun configure() {
                register(@Handler {
                    handlerSlots.withPermit { download() }
                })
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for configure: {(900, 3)}")
        result.assertOutputContains("ReqBW=2700 bytes/s")
    }

    @Test
    fun `models a declaration handler as a repeated application root`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.Handler
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            val handlerSlots = Semaphore(permits = 3)

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            @Handler
            suspend fun frameworkCallback() {
                handlerSlots.withPermit { download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(900, 3)}",
        )
        result.assertOutputContains("ReqBW=2700 bytes/s")
    }

    @Test
    fun `models an opaque annotated handler method`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthDownload
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.Handler

            interface FrameworkCallbacks {
                @Handler
                @BandwidthEffect(
                    downloads = [
                        BandwidthDownload(
                            rMaxBytesPerSecond = 700,
                            nMax = 1,
                            selfBound = 2,
                        ),
                    ],
                )
                suspend fun onRequest()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(700, 2)}",
        )
        result.assertOutputContains("ReqBW=1400 bytes/s")
    }

    @Test
    fun `rejects a declaration handler without a runtime bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.Handler
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            @Handler
            suspend fun frameworkCallback() {
                download()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("concurrent repeated @Handler function frameworkCallback")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `composes an invoke-once install contract with a nested retained handler`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.Handler
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            interface Installer {
                @BandwidthVariable("E")
                @BandwidthEffect("E")
                fun install(@BandwidthEffect("E") configure: () -> Unit)
            }

            class Client

            fun retain(callback: () -> Unit) = Unit

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            fun Client.authenticate() = Unit

            fun configure(
                installer: Installer,
                @BoundedClient(k = 3) client: Client,
            ) {
                installer.install {
                    retain(@Handler { client.authenticate() })
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for configure: {(600, 3)}")
        result.assertOutputContains("ReqBW=1800 bytes/s")
    }

    @Test
    fun `rejects a symbolic annotation on the result type`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable

            @BandwidthVariable("E")
            fun returnCallback(
                @BandwidthEffect("E") callback: () -> Unit,
            ): @BandwidthEffect("E") (() -> Unit) = callback
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Symbolic @BandwidthEffect variables are inferred on returned values",
        )
    }

    @Test
    fun `uses an image loader bound for NIA style lazy feed items`() {
        val result = compile(
            """
            package androidx.compose.foundation.lazy.staggeredgrid

            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            class LazyStaggeredGridScope
            class ImageLoader

            fun LazyVerticalStaggeredGrid(
                content: LazyStaggeredGridScope.() -> Unit,
            ) = Unit

            fun <T> LazyStaggeredGridScope.items(
                items: List<T>,
                contentType: (T) -> Any? = { null },
                itemContent: (T) -> Unit,
            ) = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            fun rememberAsyncImagePainter(
                model: String,
                imageLoader: ImageLoader,
            ) = Unit

            fun NewsFeed(
                articles: List<String>,
                @BoundedClient(k = 4) imageLoader: ImageLoader,
            ) {
                LazyVerticalStaggeredGrid {
                    items(articles) { article ->
                        rememberAsyncImagePainter(
                            model = article,
                            imageLoader = imageLoader,
                        )
                    }
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for " +
                "androidx.compose.foundation.lazy.staggeredgrid.NewsFeed: {(900, 4)}",
        )
        result.assertOutputContains("ReqBW=3600 bytes/s")
    }

    @Test
    fun `rejects NIA style lazy feed network work without a bounded client`() {
        val result = compile(
            """
            package androidx.compose.foundation.lazy.staggeredgrid

            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            class LazyStaggeredGridScope
            class ImageLoader

            fun LazyVerticalStaggeredGrid(
                content: LazyStaggeredGridScope.() -> Unit,
            ) = Unit

            fun <T> LazyStaggeredGridScope.items(
                items: List<T>,
                contentType: (T) -> Any? = { null },
                itemContent: (T) -> Unit,
            ) = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            fun rememberAsyncImagePainter(
                model: String,
                imageLoader: ImageLoader,
            ) = Unit

            fun NewsFeed(
                articles: List<String>,
                imageLoader: ImageLoader,
            ) {
                LazyVerticalStaggeredGrid {
                    items(articles) { article ->
                        rememberAsyncImagePainter(
                            model = article,
                            imageLoader = imageLoader,
                        )
                    }
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Escaping network work in repeated callback",
        )
        result.assertOutputContains(
            "Use a self bound for every download",
        )
    }

    @Test
    fun `models repeated button presses that launch bounded network work`() {
        val result = compile(
            """
            package androidx.compose.material3

            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            fun Button(
                onClick: () -> Unit,
                content: () -> Unit,
            ) = Unit

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            fun DownloadButton(
                scope: CoroutineScope,
                @BoundedClient(k = 3) client: NetworkClient,
            ) {
                Button(
                    onClick = {
                        scope.launch { client.download() }
                    },
                    content = {},
                )
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for " +
                "androidx.compose.material3.DownloadButton: {(700, 3)}",
        )
        result.assertOutputContains("ReqBW=2100 bytes/s")
    }

    @Test
    fun `charges a SAM listener callback through the repetition rule`() {
        val result = compile(
            """
            package android.view

            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            fun interface OnClickListener {
                fun onClick()
            }

            class View {
                fun setOnClickListener(listener: OnClickListener) = Unit
            }

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            fun download() = Unit

            fun bind(view: View) {
                view.setOnClickListener { download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for android.view.bind: {(900, 1)}",
        )
    }

    @Test
    fun `keeps a bounded client bound for work escaping a SAM listener`() {
        val result = compile(
            """
            package android.view

            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            fun interface OnClickListener {
                fun onClick()
            }

            class View {
                fun setOnClickListener(listener: OnClickListener) = Unit
            }

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            fun bind(
                view: View,
                scope: CoroutineScope,
                @BoundedClient(k = 3) client: NetworkClient,
            ) {
                view.setOnClickListener {
                    scope.launch { client.download() }
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for android.view.bind: {(900, 3)}",
        )
        result.assertOutputContains("ReqBW=2700 bytes/s")
    }

    @Test
    fun `rejects unbounded escaping work in a SAM listener callback`() {
        val result = compile(
            """
            package android.view

            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            fun interface OnClickListener {
                fun onClick()
            }

            class View {
                fun setOnClickListener(listener: OnClickListener) = Unit
            }

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            fun bind(view: View, scope: CoroutineScope, client: NetworkClient) {
                view.setOnClickListener {
                    scope.launch { client.download() }
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work in repeated callback")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `charges a Compose clickable callback through the repetition rule`() {
        val result = compile(
            """
            package androidx.compose.foundation

            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            class Modifier

            fun Modifier.clickable(onClick: () -> Unit): Modifier = this

            @NetworkDownload(maxBytes = 400, completeTimeoutMillis = 1_000)
            fun download() = Unit

            fun screen(modifier: Modifier) {
                modifier.clickable { download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for androidx.compose.foundation.screen: {(400, 1)}",
        )
    }

    @Test
    fun `rejects repeated button network work without a bounded client`() {
        val result = compile(
            """
            package androidx.compose.material3

            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            fun Button(
                onClick: () -> Unit,
                content: () -> Unit,
            ) = Unit

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            fun DownloadButton(
                scope: CoroutineScope,
                client: NetworkClient,
            ) {
                Button(
                    onClick = {
                        scope.launch { client.download() }
                    },
                    content = {},
                )
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work in repeated callback")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `sums sequential callee self bounds inside forEach launches`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.downloadPrimary() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.downloadSecondary() = Unit

            suspend fun foo(
                @BoundedClient(k = 2) primaryClient: NetworkClient,
                @BoundedClient(k = 3) secondaryClient: NetworkClient,
            ) = coroutineScope {
                primaryClient.downloadPrimary()
                secondaryClient.downloadSecondary()
            }

            fun loadAll(
                urls: List<String>,
                viewModelScope: CoroutineScope,
                @BoundedClient(k = 2) primaryClient: NetworkClient,
                @BoundedClient(k = 3) secondaryClient: NetworkClient,
            ) {
                urls.forEach {
                    viewModelScope.launch { foo(primaryClient, secondaryClient) }
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for loadAll: {(900, 5)}",
        )
        result.assertOutputContains("ReqBW=4500 bytes/s")
    }

    @Test
    fun `adds independent bounded clients inside one coroutine scope`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @BoundedClient(k = 2)
            val imageClient = NetworkClient()

            @BoundedClient(k = 2)
            val apiClient = NetworkClient()

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            suspend fun load() = coroutineScope {
                launch { imageClient.download() }
                launch { imageClient.download() }
                launch { imageClient.download() }
                launch { apiClient.download() }
                launch { apiClient.download() }
                launch { apiClient.download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 6)}")
        result.assertOutputContains("ReqBW=6000 bytes/s")
    }

    @Test
    fun `does not cap mixed bounded and unbounded downloads`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.async

            class NetworkClient

            @BoundedClient(k = 1)
            val client = NetworkClient()

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.boundedDownload() = Unit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun unboundedDownload() = Unit

            suspend fun load() = coroutineScope {
                val bounded = async { client.boundedDownload() }
                val unbounded = async { unboundedDownload() }
                bounded.await()
                unbounded.await()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 2)}")
        result.assertOutputContains("ReqBW=2000 bytes/s")
    }

    @Test
    fun `rejects a non-positive bounded client limit`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient

            class NetworkClient

            @BoundedClient(k = 0)
            val client = NetworkClient()
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("@BoundedClient k must be a positive constant")
    }

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
    fun `awaitAll ends overlap from a mapped async collection`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String) = Unit

            @NetworkDownload(maxBytes = 1_500, completeTimeoutMillis = 1_000)
            suspend fun afterAwaitAll() = Unit

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 4) client: NetworkClient,
            ) = coroutineScope {
                val jobs = urls.map { url ->
                    async { client.download(url) }
                }
                jobs.awaitAll()
                afterAwaitAll()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1500, 1), (900, 4)}",
        )
        result.assertOutputContains("ReqBW=3600 bytes/s")
    }

    @Test
    fun `awaitAll in a property initializer preserves overlap phases`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            @NetworkDownload(maxBytes = 1_100, completeTimeoutMillis = 1_000)
            suspend fun beforeWait() = Unit

            @NetworkDownload(maxBytes = 1_500, completeTimeoutMillis = 1_000)
            suspend fun afterWait() = Unit

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 4) client: NetworkClient,
            ) = coroutineScope {
                val jobs = urls.map { url -> async { client.download(url) } }
                beforeWait()
                val results = jobs.awaitAll()
                afterWait()
                results
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1500, 1), (1100, 5)}",
        )
        result.assertOutputContains("ReqBW=5500 bytes/s")
    }

    @Test
    fun `awaitAll in a local assignment ends child overlap`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            @NetworkDownload(maxBytes = 1_500, completeTimeoutMillis = 1_000)
            suspend fun afterWait() = Unit

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 4) client: NetworkClient,
            ) = coroutineScope {
                val jobs = urls.map { url -> async { client.download(url) } }
                var results: List<String> = emptyList()
                results = jobs.awaitAll().sortedBy { it }
                afterWait()
                results
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1500, 1), (900, 4)}")
        result.assertOutputContains("ReqBW=3600 bytes/s")
    }

    @Test
    fun `awaitAll in a chained initializer preserves enclosing callback effects`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            @NetworkDownload(maxBytes = 1_100, completeTimeoutMillis = 1_000)
            suspend fun beforeWait() = Unit

            @NetworkDownload(maxBytes = 1_700, completeTimeoutMillis = 1_000)
            suspend fun enclosingCallbackDownload() = Unit

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 4) client: NetworkClient,
            ) = coroutineScope {
                val jobs = urls.map { url -> async { client.download(url) } }
                beforeWait()
                val results = jobs.awaitAll()
                    .sortedBy { it }
                    .also { enclosingCallbackDownload() }
                results
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1700, 1), (1100, 5)}",
        )
        result.assertOutputContains("ReqBW=5500 bytes/s")
    }

    @Test
    fun `awaitAll in a chained initializer synchronizes only its own collection`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun siblingDownload() = Unit

            @NetworkDownload(maxBytes = 1_100, completeTimeoutMillis = 1_000)
            suspend fun beforeWait() = Unit

            @NetworkDownload(maxBytes = 1_700, completeTimeoutMillis = 1_000)
            suspend fun betweenWaits() = Unit

            @NetworkDownload(maxBytes = 2_000, completeTimeoutMillis = 1_000)
            suspend fun afterBothWaits() = Unit

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 4) client: NetworkClient,
            ) = coroutineScope {
                val jobs = urls.map { url -> async { client.download(url) } }
                val sibling = async { siblingDownload() }
                beforeWait()
                val results = jobs.awaitAll().sortedBy { it }
                betweenWaits()
                sibling.await()
                afterBothWaits()
                results
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(2000, 1), (1700, 2), (1100, 6)}",
        )
        result.assertOutputContains("ReqBW=6600 bytes/s")
    }

    @Test
    fun `awaitAll in a nested initializer still rejects an untracked Deferred collection`() {
        val result = compile(
            """
            import kotlinx.coroutines.Deferred
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            suspend fun load(jobs: List<Deferred<String>>) = coroutineScope {
                val results = jobs.awaitAll().sortedBy { it }
                results
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Cannot infer awaitAll for an untracked Deferred collection",
        )
    }

    @Test
    fun `awaitAll in an argument preserves evaluation order around the wait`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            @NetworkDownload(maxBytes = 1_100, completeTimeoutMillis = 1_000)
            suspend fun beforeWait() = Unit

            @NetworkDownload(maxBytes = 1_500, completeTimeoutMillis = 1_000)
            suspend fun afterWait() = Unit

            fun combine(before: Unit, values: List<String>, after: Unit): List<String> = values

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 4) client: NetworkClient,
            ) = coroutineScope {
                val jobs = urls.map { url -> async { client.download(url) } }
                val results = combine(beforeWait(), jobs.awaitAll(), afterWait())
                results
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1500, 1), (1100, 5)}",
        )
        result.assertOutputContains("ReqBW=5500 bytes/s")
    }

    @Test
    fun `rejects awaitAll hidden in a deferred lambda rather than synchronizing outer children`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 4) client: NetworkClient,
            ) = coroutineScope {
                val jobs = urls.map { url -> async { client.download(url) } }
                val waitLater: suspend () -> Unit = {
                    jobs.awaitAll()
                    Unit
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Cannot infer awaitAll for an untracked Deferred collection",
        )
    }

    @Test
    fun `rejects conditional awaitAll rather than synchronizing outer children unconditionally`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 4) client: NetworkClient,
                waitNow: Boolean,
            ) = coroutineScope {
                val jobs = urls.map { url -> async { client.download(url) } }
                val results = if (waitNow) jobs.awaitAll() else emptyList<String>()
                results
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Cannot infer awaitAll for an untracked Deferred collection",
        )
    }

    @Test
    fun `await stored inside a deferred lambda does not synchronize outer children`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun childDownload() = Unit

            @NetworkDownload(maxBytes = 1_500, completeTimeoutMillis = 1_000)
            suspend fun foregroundDownload() = Unit

            suspend fun load() = coroutineScope {
                val child = async { childDownload() }
                val waitLater: suspend () -> Unit = { child.await() }
                foregroundDownload()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1500, 2)}")
        result.assertOutputContains("ReqBW=3000 bytes/s")
    }

    @Test
    fun `conditional await does not synchronize outer children unconditionally`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun childDownload() = Unit

            @NetworkDownload(maxBytes = 1_500, completeTimeoutMillis = 1_000)
            suspend fun foregroundDownload() = Unit

            suspend fun load(waitNow: Boolean) = coroutineScope {
                val child = async { childDownload() }
                val result = if (waitNow) child.await() else Unit
                foregroundDownload()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1500, 2)}")
        result.assertOutputContains("ReqBW=3000 bytes/s")
    }

    @Test
    fun `mapped callback destructuring and pure preparation preserve the awaited child group`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            @NetworkDownload(maxBytes = 1_500, completeTimeoutMillis = 1_000)
            suspend fun afterWait() = Unit

            suspend fun load(
                servers: Map<String, Boolean>,
                @BoundedClient(k = 3) client: NetworkClient,
            ) = coroutineScope {
                val jobs = servers.filter { it.value }.map { (url, _) ->
                    val prepared = url.trim()
                    async { client.download(prepared) }
                }
                jobs.awaitAll()
                afterWait()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1500, 1), (900, 3)}",
        )
        result.assertOutputContains("ReqBW=2700 bytes/s")
    }

    @Test
    fun `mapped callback completing preparation overlaps earlier children without a self bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 1_100, completeTimeoutMillis = 1_000)
            suspend fun prepare(url: String): String = url

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            @NetworkDownload(maxBytes = 1_700, completeTimeoutMillis = 1_000)
            suspend fun afterWait() = Unit

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 4) client: NetworkClient,
            ) = coroutineScope {
                val jobs = urls.map { url ->
                    val prepared = prepare(url)
                    async { client.download(prepared) }
                }
                jobs.awaitAll()
                afterWait()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1700, 1), (1100, 5)}",
        )
        result.assertOutputContains("ReqBW=5500 bytes/s")
    }

    @Test
    fun `mapped callback escaping preparation remains active after awaiting returned children`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.backgroundDownload() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            @NetworkDownload(maxBytes = 1_700, completeTimeoutMillis = 1_000)
            suspend fun afterWait() = Unit

            suspend fun load(
                urls: List<String>,
                externalScope: CoroutineScope,
                @BoundedClient(k = 2) backgroundClient: NetworkClient,
                @BoundedClient(k = 3) client: NetworkClient,
            ) = coroutineScope {
                val jobs = urls.map { url ->
                    externalScope.launch { backgroundClient.backgroundDownload() }
                    async { client.download(url) }
                }
                jobs.awaitAll()
                afterWait()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1700, 3), (900, 5), (500, 5)}",
        )
        result.assertOutputContains("ReqBW=5100 bytes/s")
    }

    @Test
    fun `mapped callback conditional child returns remain untracked`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 3) client: NetworkClient,
                alternative: Boolean,
            ) = coroutineScope {
                val jobs = urls.map { url ->
                    if (alternative) async { client.download(url) }
                    else async { client.download(url) }
                }
                jobs.awaitAll()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Cannot infer awaitAll for an untracked Deferred collection",
        )
    }

    @Test
    fun `mapped callback opaque Deferred returns remain untracked`() {
        val result = compile(
            """
            import kotlinx.coroutines.Deferred
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            fun opaqueDeferred(): Deferred<String> = error("not executed")

            suspend fun load(urls: List<String>) = coroutineScope {
                val jobs = urls.map { url ->
                    val prepared = url.trim()
                    opaqueDeferred()
                }
                jobs.awaitAll()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Cannot infer awaitAll for an untracked Deferred collection",
        )
    }

    @Test
    fun `mapped callback unbounded escaping preparation requires a self bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun backgroundDownload() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            suspend fun load(
                urls: List<String>,
                externalScope: CoroutineScope,
                @BoundedClient(k = 3) client: NetworkClient,
            ) = coroutineScope {
                val jobs = urls.map { url ->
                    externalScope.launch { backgroundDownload() }
                    async { client.download(url) }
                }
                jobs.awaitAll()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("self bound")
    }

    @Test
    fun `mapped callback alternate early return is not associated with the final async`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.Deferred
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String): String = url

            fun opaqueDeferred(): Deferred<String> = error("not executed")

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 3) client: NetworkClient,
                returnOpaque: Boolean,
            ) = coroutineScope {
                val jobs = urls.map { url ->
                    if (returnOpaque) return@map opaqueDeferred()
                    async { client.download(url) }
                }
                jobs.awaitAll()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Cannot infer awaitAll for an untracked Deferred collection",
        )
    }

    @Test
    fun `infers a directly awaited mapped async collection`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            class NetworkClient

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(url: String) = Unit

            suspend fun load(
                urls: List<String>,
                @BoundedClient(k = 3) client: NetworkClient,
            ) = coroutineScope {
                urls.map { url ->
                    async { client.download(url) }
                }.awaitAll()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 3)}")
        result.assertOutputContains("ReqBW=2100 bytes/s")
    }

    @Test
    fun `rejects mapped async downloads without self bounds`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download(url: String) = Unit

            suspend fun load(urls: List<String>) = coroutineScope {
                val jobs = urls.map { url ->
                    async { download(url) }
                }
                jobs.awaitAll()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Mapped async children may overlap across an unknown number of collection elements",
        )
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `rejects awaitAll on an untracked Deferred collection`() {
        val result = compile(
            """
            import kotlinx.coroutines.Deferred
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            suspend fun load(jobs: List<Deferred<Unit>>) = coroutineScope {
                jobs.awaitAll()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Cannot infer awaitAll for an untracked Deferred collection",
        )
    }

    @Test
    fun `concurrent map contract keeps helper returned and stored pipelines lazy`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.filterNotNull

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun <T, R> Flow<T>.boundedMap(
                maxConcurrency: Int,
                buffer: Int,
                f: suspend (T) -> R,
            ): Flow<R> = error("audited library boundary")

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun download(url: String): String? = url

            fun downloads(urls: List<String>): Flow<String?> =
                urls.asFlow().boundedMap(buffer = 4, maxConcurrency = 3) { download(it) }

            fun buildOnly(urls: List<String>) {
                val pipeline = downloads(urls)
            }

            suspend fun load(urls: List<String>) {
                val pipeline = downloads(urls)
                pipeline.filterNotNull().collect { value -> println(value) }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for downloads:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 3)}")
        result.assertOutputContains("ReqBW=2100 bytes/s")
    }

    @Test
    fun `concurrent map contract preserves correlations across finite callback copies`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.launch

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun <T, R> Flow<T>.boundedMap(
                maxConcurrency: Int,
                buffer: Int,
                f: suspend (T) -> R,
            ): Flow<R> = error("audited library boundary")

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun heavy() = Unit

            @NetworkDownload(maxBytes = 1, completeTimeoutMillis = 1_000)
            suspend fun light() = Unit

            suspend fun stage() {
                heavy()
                coroutineScope {
                    launch { light() }
                    launch { light() }
                    launch { light() }
                }
            }

            suspend fun load() {
                listOf(1).asFlow().boundedMap(3, 4) { stage() }.collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for stage: {(100, 1), (1, 3)}")
        result.assertOutputContains("Inferred bandwidth effect for load: {(100, 7), (1, 9)}")
        result.assertOutputContains("ReqBW=700 bytes/s")
    }

    @Test
    fun `concurrent map contract overlaps upstream work with bounded callbacks`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun <T, R> Flow<T>.boundedMap(
                maxConcurrency: Int,
                buffer: Int,
                f: suspend (T) -> R,
            ): Flow<R> = error("audited library boundary")

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun nextItem(): Int = 1

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun download(item: Int) = Unit

            suspend fun load() {
                flow { emit(nextItem()) }.boundedMap(3, 4) { download(it) }.collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(500, 4)}")
        result.assertOutputContains("ReqBW=2000 bytes/s")
    }

    @Test
    fun `concurrent map contract rejects zero and runtime concurrency caps`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun <T, R> Flow<T>.boundedMap(
                maxConcurrency: Int,
                buffer: Int,
                f: suspend (T) -> R,
            ): Flow<R> = error("audited library boundary")

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun download(item: Int) = Unit

            suspend fun withZero() {
                listOf(1).asFlow().boundedMap(0, 4) { download(it) }.collect()
            }

            suspend fun withRuntimeCap(limit: Int) {
                listOf(1).asFlow().boundedMap(limit, 4) { download(it) }.collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("concurrency must be an explicit positive constant")
    }

    @Test
    fun `concurrent map contract rejects callback work escaping without a self bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.launch

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun <T, R> Flow<T>.boundedMap(
                maxConcurrency: Int,
                buffer: Int,
                f: suspend (T) -> R,
            ): Flow<R> = error("audited library boundary")

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun download(item: Int) = Unit

            suspend fun load(scope: CoroutineScope) {
                listOf(1).asFlow().boundedMap(3, 4) { item ->
                    scope.launch { download(item) }
                }.collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("escaping")
        result.assertOutputContains("self bound")
    }

    @Test
    fun `concurrent map contract rejects losing the dependent cap through a callable reference`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.asFlow

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun <T, R> Flow<T>.boundedMap(
                maxConcurrency: Int,
                buffer: Int,
                f: suspend (T) -> R,
            ): Flow<R> = error("audited library boundary")

            fun load() {
                val source = listOf(1).asFlow()
                val operator: (Int, Int, suspend (Int) -> Unit) -> Flow<Unit> = source::boundedMap
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("callable reference")
    }

    @Test
    fun `concurrent map contract rejects malformed signatures instead of trusting them`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.asFlow

            @BandwidthConcurrentMap(concurrencyParameter = "missing", callbackParameter = "f")
            fun Flow<Int>.missingCap(maxConcurrency: Int, f: suspend (Int) -> Int): Flow<Int> = this

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun Flow<Int>.wrongCapType(maxConcurrency: Long, f: suspend (Int) -> Int): Flow<Int> = this

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun List<Int>.wrongReceiver(maxConcurrency: Int, f: suspend (Int) -> Int): Flow<Int> = asFlow()

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun Flow<Int>.extraCallback(
                maxConcurrency: Int,
                f: suspend (Int) -> Int,
                onFinish: () -> Unit,
            ): Flow<Int> = this
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("concurrency parameter 'missing' does not exist")
        result.assertOutputContains("concurrency parameter 'maxConcurrency' must have type Int")
        result.assertOutputContains("requires a Flow extension receiver")
        result.assertOutputContains("does not support additional function-valued or Flow")
    }

    @Test
    fun `concurrent map contract substitutes effects through a noninline polymorphic helper`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun <T, R> Flow<T>.boundedMap(
                maxConcurrency: Int,
                buffer: Int,
                f: suspend (T) -> R,
            ): Flow<R> = error("audited library boundary")

            @BandwidthVariable("U", "E")
            fun <T, R> forward(
                @BandwidthEffect("U") source: Flow<T>,
                @BandwidthEffect("E") transform: suspend (T) -> R,
            ): Flow<R> = source.boundedMap(3, 4, transform)

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun nextItem(): Int = 1

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun download(item: Int) = Unit

            suspend fun load() {
                val pipeline = forward(flow { emit(nextItem()) }) { download(it) }
                pipeline.collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(900, 4)}")
        result.assertOutputContains("ReqBW=3600 bytes/s")
    }

    @Test
    fun `concurrent map contract rejects latent callback results and hidden extra latent arguments`() {
        val returnedFlowResult = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun <T, R> Flow<T>.boundedMap(
                maxConcurrency: Int,
                buffer: Int,
                f: suspend (T) -> R,
            ): Flow<R> = error("audited library boundary")

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun load() {
                listOf(1).asFlow().boundedMap(3, 4) {
                    flow { emit(download()) }
                }.collect()
            }
            """,
        )

        assertNotEquals(0, returnedFlowResult.exitCode, returnedFlowResult.output)
        returnedFlowResult.assertOutputContains("callbacks returning latent")

        listOf("flow { emit(download()) }", "{ Unit }").forEach { extraArgument ->
            val extraArgumentResult = compile(
                """
                import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import kotlinx.coroutines.flow.Flow
                import kotlinx.coroutines.flow.asFlow
                import kotlinx.coroutines.flow.collect
                import kotlinx.coroutines.flow.flow

                @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
                fun <T, R, S> Flow<T>.boundedMapWithExtra(
                    maxConcurrency: Int,
                    extra: S,
                    f: suspend (T) -> R,
                ): Flow<R> = error("audited library boundary")

                @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
                suspend fun download() = Unit

                suspend fun load() {
                    listOf(1).asFlow().boundedMapWithExtra(3, $extraArgument) { Unit }.collect()
                }
                """,
            )

            assertNotEquals(0, extraArgumentResult.exitCode, extraArgumentResult.output)
            extraArgumentResult.assertOutputContains("additional latent")
        }
    }

    @Test
    fun `concurrent map contract rejects a nullable concurrency parameter declaration`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
            import kotlinx.coroutines.flow.Flow

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun <T, R> Flow<T>.boundedMap(
                maxConcurrency: Int?,
                buffer: Int,
                f: suspend (T) -> R,
            ): Flow<R> = error("audited library boundary")
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("concurrency parameter 'maxConcurrency' must have type Int")
    }

    @Test
    fun `bounds flatMapMerge inner flows until collect`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.ExperimentalCoroutinesApi
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flatMapMerge
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            suspend fun download(url: String) = Unit

            @NetworkDownload(maxBytes = 1_500, completeTimeoutMillis = 1_000)
            suspend fun afterCollect() = Unit

            @OptIn(ExperimentalCoroutinesApi::class)
            suspend fun load(urls: List<String>) {
                urls.asFlow()
                    .flatMapMerge(concurrency = 4) { url ->
                        flow { emit(download(url)) }
                    }
                    .collect()
                afterCollect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for load: {(1500, 1), (800, 4)}",
        )
        result.assertOutputContains("ReqBW=3200 bytes/s")
    }

    @Test
    fun `tracks a stored flatMapMerge pipeline without running it eagerly`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.ExperimentalCoroutinesApi
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flatMapMerge
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun download(url: String) = Unit

            @OptIn(ExperimentalCoroutinesApi::class)
            suspend fun buildOnly(urls: List<String>) {
                val downloads = urls.asFlow()
                    .flatMapMerge(concurrency = 3) { url ->
                        flow { emit(download(url)) }
                    }
            }

            @OptIn(ExperimentalCoroutinesApi::class)
            suspend fun load(urls: List<String>) {
                val downloads = urls.asFlow()
                    .flatMapMerge(concurrency = 3) { url ->
                        flow { emit(download(url)) }
                    }
                downloads.collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(
            result.output.contains("Inferred bandwidth effect for buildOnly:"),
            result.output,
        )
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 3)}")
        result.assertOutputContains("ReqBW=2100 bytes/s")
    }

    @Test
    fun `collect callback effects materialize network work over a pure upstream`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            suspend fun download(url: String) = Unit

            suspend fun load(urls: List<String>) {
                urls.asFlow().collect { url -> download(url) }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(600, 1)}")
        result.assertOutputContains("ReqBW=600 bytes/s")
    }

    @Test
    fun `collect callback effects keep nested bounded download batches sequential`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthConcurrentMap
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect

            @BandwidthConcurrentMap(concurrencyParameter = "maxConcurrency", callbackParameter = "f")
            fun <T, R> Flow<T>.boundedMap(
                maxConcurrency: Int,
                buffer: Int,
                f: suspend (T) -> R,
            ): Flow<R> = error("audited library boundary")

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun download(segment: String) = Unit

            suspend fun load() {
                listOf(listOf("a", "b", "c"), listOf("d", "e", "f")).asFlow()
                    .collect { segments ->
                        segments.asFlow()
                            .boundedMap(3, 4) { download(it) }
                            .collect { result -> println(result) }
                    }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 3)}")
        result.assertOutputContains("ReqBW=2100 bytes/s")
    }

    @Test
    fun `collect callback effects conservatively overlap producer and consumer downloads`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.buffer
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun nextItem(): String = "segment"

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            suspend fun download(segment: String) = Unit

            suspend fun load() {
                flow {
                    emit(nextItem())
                    emit(nextItem())
                }.buffer(1).collect { download(it) }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 2)}")
        result.assertOutputContains("ReqBW=1400 bytes/s")
    }

    @Test
    fun `collect callback effects reject unbounded work escaping each invocation`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun download(segment: String) = Unit

            suspend fun load(scope: CoroutineScope, segments: List<String>) {
                segments.asFlow().collect { segment ->
                    scope.launch { download(segment) }
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `collect callback effects retain self bounded escaping work after collection`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(segment: String) = Unit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun afterCollect() = Unit

            suspend fun load(
                scope: CoroutineScope,
                segments: List<String>,
                @BoundedClient(k = 3) client: NetworkClient,
            ) {
                segments.asFlow().collect { segment ->
                    scope.launch { client.download(segment) }
                }
                afterCollect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        // The lower-rate pair retains escaping lifetime and self-bound metadata.
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 4), (700, 4)}")
        result.assertOutputContains("ReqBW=4000 bytes/s")
    }

    @Test
    fun `collect callback effects substitute both producer and callback effect variables`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @BandwidthVariable("U", "E")
            suspend fun <T> consume(
                @BandwidthEffect("U") source: Flow<T>,
                @BandwidthEffect("E") action: suspend (T) -> Unit,
            ) {
                source.collect(action)
            }

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun nextItem(): String = "segment"

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun download(segment: String) = Unit

            suspend fun load() {
                consume(flow { emit(nextItem()) }) { download(it) }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(900, 2)}")
        result.assertOutputContains("ReqBW=1800 bytes/s")
    }

    @Test
    fun `collect callback effects preserve eager construction of an empty callback`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect

            @NetworkDownload(maxBytes = 1_200, completeTimeoutMillis = 1_000)
            suspend fun prepareCollector() = Unit

            suspend fun collector(): suspend (Int) -> Unit {
                prepareCollector()
                return { value -> println(value) }
            }

            suspend fun load() {
                listOf(1, 2).asFlow().collect(collector())
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1200, 1)}")
        result.assertOutputContains("ReqBW=1200 bytes/s")
    }

    @Test
    fun `collect callback effects keep untimed downloads in producer concurrency`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.buffer
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun nextItem(): String = "segment"

            @BandwidthEffect(rMaxBytesPerSecond = 0, nMax = 1)
            suspend fun untimedDownload(segment: String) = Unit

            suspend fun load() {
                flow {
                    emit(nextItem())
                    emit(nextItem())
                }.buffer(1).collect { untimedDownload(it) }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(500, 2)}")
        result.assertOutputContains("ReqBW=1000 bytes/s")
    }

    @Test
    fun `collect callback effects do not execute a discarded returned cold Flow`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun nextItem(): String = "segment"

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download(segment: String) = Unit

            suspend fun load() {
                flow { emit(nextItem()) }.collect { segment ->
                    flow { emit(download(segment)) }
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(500, 1)}")
        result.assertOutputContains("ReqBW=500 bytes/s")
    }

    @Test
    fun `collect callback effects still reject an opaque Flow producer`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect

            interface Source {
                fun stream(): Flow<String>
            }

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            suspend fun download(segment: String) = Unit

            suspend fun load(source: Source) {
                source.stream().collect { download(it) }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer collect on an untracked Flow")
    }

    @Test
    fun `collect callback effects do not assume an opaque FlowCollector object is empty`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.FlowCollector
            import kotlinx.coroutines.flow.asFlow

            suspend fun load(collector: FlowCollector<Int>) {
                listOf(1, 2).asFlow().collect(collector)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer the Flow collector callback effect")
    }

    @Test
    fun `accepts an empty Flow collector callback without losing upstream work`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun download(): String = "content"

            suspend fun load(): String {
                val result = StringBuilder()
                flow { emit(download()) }.collect { text -> result.append(text) }
                return result.toString()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
        result.assertOutputContains("ReqBW=700 bytes/s")
    }

    @Test
    fun `preserves upstream Flow work through repeated cancellable operators`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.cancellable
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 710, completeTimeoutMillis = 1_000)
            suspend fun download(): String = "content"

            suspend fun load(): String {
                val result = StringBuilder()
                flow { emit(download()) }
                    .cancellable()
                    .cancellable()
                    .collect { text -> result.append(text) }
                return result.toString()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(710, 1)}")
        result.assertOutputContains("ReqBW=710 bytes/s")
    }

    @Test
    fun `preserves upstream work through a nullable Flow and safe call collection`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 725, completeTimeoutMillis = 1_000)
            suspend fun download(): String = "content"

            fun stream(): Flow<String>? = flow { emit(download()) }

            suspend fun load(): String {
                val result = StringBuilder()
                val source = stream()
                source?.collect { text -> result.append(text) }
                return result.toString()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(725, 1)}")
        result.assertOutputContains("ReqBW=725 bytes/s")
    }

    @Test
    fun `materializes a concrete Flow parameter contract at collection`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 750, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun consume(
                @BandwidthEffect(rMaxBytesPerSecond = 750, nMax = 1) source: Flow<Unit>,
            ) {
                source.collect()
            }

            suspend fun load() = consume(flow { emit(download()) })
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for consume: {(750, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for load: {(750, 1)}")
    }

    @Test
    fun `uses a Flow contract placed on the parameter type`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 760, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun consume(
                source: @BandwidthEffect(rMaxBytesPerSecond = 760, nMax = 1) Flow<Unit>,
            ) {
                source.collect()
            }

            suspend fun load() = consume(flow { emit(download()) })
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for consume: {(760, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for load: {(760, 1)}")
    }

    @Test
    fun `accepts a known empty Flow through an unannotated parameter`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect

            suspend fun consume(source: Flow<Int>) {
                source.collect { value -> value.toString() }
            }

            suspend fun load() = consume(listOf(1, 2).asFlow())
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for consume:"), result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for load:"), result.output)
    }

    @Test
    fun `rejects a nonempty Flow passed to an unannotated parameter`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 775, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun consume(source: Flow<Unit>) {
                source.collect()
            }

            suspend fun load() = consume(flow { emit(download()) })
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Effectful higher-order argument for parameter 'source'")
    }

    @Test
    fun `uses a declared latent effect on an opaque Flow return`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect

            interface Source {
                fun stream(): @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<String>
            }

            suspend fun load(source: Source) {
                source.stream().collect { value -> value.length }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(800, 1)}")
    }

    @Test
    fun `checks a visible Flow return against its latent contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            fun stream(): @BandwidthEffect(rMaxBytesPerSecond = 500, nMax = 1) Flow<Unit> =
                flow { emit(download()) }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred returned latent effect {(900, 1)} is not covered")
    }

    @Test
    fun `accepts an empty Flow implementation under an empty inherited return contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            interface Source {
                fun stream(): @BandwidthEffect() Flow<String>
            }

            class LocalSource : Source {
                override fun stream(): Flow<String> = flow { emit("local content") }
            }

            suspend fun load(source: Source) {
                source.stream().collect { text -> text.length }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for load:"), result.output)
    }

    @Test
    fun `rejects effectful Flow implementation under an empty inherited return contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download(): String = "network content"

            interface Source {
                fun stream(): @BandwidthEffect() Flow<String>
            }

            class NetworkSource : Source {
                override fun stream(): Flow<String> = flow { emit(download()) }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred returned latent effect {(900, 1)}")
        result.assertOutputContains("overridden @BandwidthEffect contract {}")
    }

    @Test
    fun `preserves returned Flow work under an empty eager factory contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 825, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            @BandwidthEffect()
            fun stream(): Flow<Unit> = flow { emit(download()) }

            fun buildOnly() {
                stream()
            }

            suspend fun load() {
                stream().collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for stream:"), result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(825, 1)}")
    }

    @Test
    fun `does not treat an opaque unannotated Flow return as empty`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect

            interface Source {
                fun stream(): Flow<String>
            }

            suspend fun load(source: Source) {
                source.stream().collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer collect on an untracked Flow")
    }

    @Test
    fun `does not turn an opaque Flow into empty through a parameter`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect

            interface Source {
                fun stream(): Flow<String>
            }

            suspend fun consume(source: Flow<String>) {
                source.collect()
            }

            suspend fun load(source: Source) = consume(source.stream())
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot pass an untracked Flow to parameter 'source'")
    }

    @Test
    fun `does not hide an unknown Flow behind a known empty conditional branch`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            interface Source {
                fun stream(): Flow<Int>
            }

            suspend fun consume(source: Flow<Int>) {
                source.collect()
            }

            suspend fun load(boundary: Source, useLocal: Boolean) {
                val selected = if (useLocal) flow { emit(1) } else boundary.stream()
                consume(selected)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("untracked Flow")
    }

    @Test
    fun `does not hide an unknown Flow assignment behind an empty initializer`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            interface Source {
                fun stream(): Flow<Int>
            }

            suspend fun consume(source: Flow<Int>) {
                source.collect()
            }

            suspend fun load(boundary: Source, useRemote: Boolean) {
                var selected: Flow<Int> = flow { emit(1) }
                if (useRemote) selected = boundary.stream()
                consume(selected)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("untracked Flow")
    }

    @Test
    fun `rejects a cached helper summary capturing mutable Flow storage`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            interface Source {
                fun stream(): Flow<Int>
            }

            suspend fun load(boundary: Source) {
                var selected: Flow<Int> = flow { emit(1) }
                fun current(): Flow<Int> = selected

                current().collect()
                selected = boundary.stream()
                current().collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("untracked Flow")
    }

    @Test
    fun `does not assume an omitted unknown Flow default argument is empty`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect

            interface Source {
                fun stream(): Flow<Int>
            }

            suspend fun consume(boundary: Source, source: Flow<Int> = boundary.stream()) {
                source.collect()
            }

            suspend fun load(boundary: Source) {
                consume(boundary)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("untracked Flow")
    }

    @Test
    fun `substitutes a symbolic Flow effect through a visible passthrough helper`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 850, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            @BandwidthVariable("E")
            fun <T> forward(@BandwidthEffect("E") source: Flow<T>): Flow<T> = source

            suspend fun load() {
                forward(flow { emit(download()) }).collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for forward:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(850, 1)}")
    }

    @Test
    fun `latent Flow discipline preserves a polymorphic extension receiver without eagerly collecting it`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 850, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            @BandwidthVariable("E")
            fun <T> @BandwidthEffect("E") Flow<T>.forward(): Flow<T> = this

            fun buildOnly() {
                flow { emit(download()) }.forward()
            }

            suspend fun load() {
                flow { emit(download()) }.forward().collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(850, 1)}")
    }

    @Test
    fun `latent Flow discipline checks a concrete extension receiver contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>.consume() {
                collect()
            }

            suspend fun load() = flow { emit(download()) }.consume()
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(800, 1)}")
    }

    @Test
    fun `latent Flow discipline rejects an effect exceeding the extension receiver contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>.consume() {
                collect()
            }

            suspend fun load() = flow { emit(download()) }.consume()
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline defaults an unannotated extension receiver to empty`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect

            suspend fun Flow<Int>.consume() {
                collect { println(it) }
            }

            suspend fun load() = listOf(1, 2).asFlow().consume()
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for load:"), result.output)
    }

    @Test
    fun `latent Flow discipline rejects an effectful unannotated extension receiver`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun Flow<Unit>.consume() {
                collect()
            }

            suspend fun load() = flow { emit(download()) }.consume()
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow receiver")
    }

    @Test
    fun `latent Flow discipline preserves a labeled receiver captured by a returned flow builder`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 825, completeTimeoutMillis = 1_000)
            suspend fun download(): String = "content"

            @BandwidthVariable("E")
            fun <T> @BandwidthEffect("E") Flow<T>.relay(): Flow<T> = flow {
                this@relay.collect { emit(it) }
            }

            fun buildOnly() {
                flow { emit(download()) }.relay()
            }

            suspend fun load() {
                flow { emit(download()) }.relay().collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(825, 1)}")
    }

    @Test
    fun `latent Flow discipline separates eager extension construction from returned collection`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            suspend fun prepare() = Unit

            @NetworkDownload(maxBytes = 850, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            @BandwidthVariable("E")
            suspend fun <T> @BandwidthEffect("E") Flow<T>.prepareRelay(): Flow<T> {
                prepare()
                return flow { this@prepareRelay.collect { emit(it) } }
            }

            suspend fun buildOnly() {
                flow { emit(download()) }.prepareRelay()
            }

            suspend fun load() {
                flow { emit(download()) }.prepareRelay().collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for buildOnly: {(300, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for load: {(850, 1)}")
    }

    @Test
    fun `latent Flow discipline preserves a concrete val constructor property contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source(val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>) {
                suspend fun read() { stream.collect() }
            }

            fun buildOnly() = Source(flow { emit(download()) })

            suspend fun load(source: Source) { source.read() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for Source.read: {(800, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for load: {(800, 1)}")
    }

    @Test
    fun `latent Flow discipline preserves a concrete mutable constructor property contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source(var stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>) {
                fun replace() { stream = flow { emit(download()) } }
                suspend fun read() { stream.collect() }
            }

            suspend fun load(source: Source) {
                source.replace()
                source.stream.collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for Source.read: {(800, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for load: {(800, 1)}")
    }

    @Test
    fun `latent Flow discipline rejects a constructor argument exceeding its property contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source(val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>)

            fun load() = Source(flow { emit(download()) })
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline rejects an effectful argument to an unannotated constructor property`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 750, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source(val stream: Flow<Unit>)

            fun load() = Source(flow { emit(download()) })
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("parameter 'stream'")
    }

    @Test
    fun `latent Flow discipline rejects an effectful initializer of an unannotated member property`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 750, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source {
                val stream: Flow<Unit> = flow { emit(download()) }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow storage contract")
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline checks an annotated member initializer and uses its declared bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source {
                val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit> =
                    flow { emit(download()) }

                suspend fun read() { stream.collect() }
            }

            suspend fun load(source: Source) { source.stream.collect() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for Source.read: {(800, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for load: {(800, 1)}")
    }

    @Test
    fun `latent Flow discipline rejects a member assignment exceeding its storage contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source {
                var stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit> = flow { emit(Unit) }

                fun replace() { stream = flow { emit(download()) } }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow storage contract")
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline rejects an external setter argument exceeding its storage contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source(var stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>)

            fun replace(source: Source) { source.stream = flow { emit(download()) } }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow storage contract")
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline rejects an unknown member initializer even without collection`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import kotlinx.coroutines.flow.Flow

            interface Boundary {
                fun stream(): Flow<Unit>
            }

            class Source(boundary: Boundary) {
                val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit> = boundary.stream()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow storage contract")
    }

    @Test
    fun `latent Flow discipline uses a stable property contract across different instances`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun small() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun large() = Unit

            class Source(val stream: @BandwidthEffect(rMaxBytesPerSecond = 1_000, nMax = 1) Flow<Unit>)

            fun low() = Source(flow { emit(small()) })
            fun high() = Source(flow { emit(large()) })

            suspend fun read(source: Source) { source.stream.collect() }

            suspend fun load() {
                read(low())
                read(high())
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for low:"), result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for high:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for read: {(1000, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 1)}")
    }

    @Test
    fun `latent Flow discipline rejects a symbolic effect passed to a concrete receiver contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import kotlinx.coroutines.flow.Flow

            fun <T> @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<T>.fixedReceiver() = Unit

            @BandwidthVariable("U")
            fun <T> relay(@BandwidthEffect("U") source: Flow<T>) {
                source.fixedReceiver()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline rejects a symbolic effect passed to a concrete parameter contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import kotlinx.coroutines.flow.Flow

            fun <T> fixedParameter(source: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<T>) = Unit

            @BandwidthVariable("U")
            fun <T> relay(@BandwidthEffect("U") source: Flow<T>) {
                fixedParameter(source)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline checks init block assignments against storage contracts`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source {
                var stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit> = flow { emit(Unit) }

                init {
                    stream = flow { emit(download()) }
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow storage contract")
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline checks secondary constructor assignments against storage contracts`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source {
                var stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit> = flow { emit(Unit) }

                constructor(useRemote: Boolean) {
                    if (useRemote) stream = flow { emit(download()) }
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow storage contract")
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline checks custom setter backing field assignments`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source {
                var stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit> = flow { emit(Unit) }
                    set(value) {
                        field = flow { emit(download()) }
                    }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow storage contract")
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline checks a custom getter against its property contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source {
                val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>
                    get() = flow { emit(download()) }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline rejects symbolic contracts on class storage`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import kotlinx.coroutines.flow.Flow

            @BandwidthVariable("E")
            fun <T> build(@BandwidthEffect("E") source: Flow<T>) {
                class Source(val stream: @BandwidthEffect("E") Flow<T>)
                Source(source)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        assertEquals(
            true,
            "concrete" in result.output ||
                "is not declared by @BandwidthVariable" in result.output,
            result.output,
        )
    }

    @Test
    fun `latent Flow discipline charges eager custom getter work without collecting its return`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            fun prepare() = Unit

            class Source {
                val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>
                    get() {
                        prepare()
                        return flow { emit(Unit) }
                    }
            }

            fun readOnly(source: Source) { source.stream }

            suspend fun load(source: Source) { source.stream.collect() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for readOnly: {(300, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for load: {(800, 1)}")
    }

    @Test
    fun `latent Flow discipline charges eager custom setter work on property assignment`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 350, completeTimeoutMillis = 1_000)
            fun prepare() = Unit

            class Source {
                var stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit> = flow { emit(Unit) }
                    set(value) {
                        prepare()
                        field = value
                    }
            }

            fun replace(source: Source) { source.stream = flow { emit(Unit) } }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for replace: {(350, 1)}")
    }

    @Test
    fun `local anonymous property owner supports ordinary overrides without global lookup`() {
        val result = compile(
            """
            interface Session { var sessionId: String? }
            fun createSession(): Session = object : Session {
                override var sessionId: String? = null
            }
            """,
        )
        assertEquals(0, result.exitCode, result.output)
    }

    @Test
    fun `local anonymous Flow owner preserves inherited contract checks`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow
            interface Source {
                val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>
            }
            fun createSource(): Source = object : Source {
                override val stream: @BandwidthEffect(rMaxBytesPerSecond = 900, nMax = 1) Flow<Unit> = flow { emit(Unit) }
            }
            """,
        )
        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("overridden property contract")
    }

    @Test
    fun `local anonymous Flow wrapper checks non Flow eager initializer`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow
            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1000)
            fun download(): String = "data"
            fun createSource(): Any = object {
                val stream: @BandwidthEffect() Flow<Unit> = flow { emit(Unit) }
                val eager = download()
            }
            """,
        )
        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow wrapper initializer")
    }

    @Test
    fun `latent Flow discipline rejects a val override widening its inherited latent effect`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            open class Base {
                open val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit> = flow { emit(Unit) }
            }

            class Derived : Base() {
                override val stream: @BandwidthEffect(rMaxBytesPerSecond = 900, nMax = 1) Flow<Unit> = flow { emit(Unit) }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("overridden property contract")
    }

    @Test
    fun `latent Flow discipline rejects a var override narrowing the accepted storage effect`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            open class Base {
                open var stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit> = flow { emit(Unit) }
            }

            class Derived : Base() {
                override var stream: @BandwidthEffect(rMaxBytesPerSecond = 700, nMax = 1) Flow<Unit> = flow { emit(Unit) }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Mutable Flow storage must preserve")
    }

    @Test
    fun `latent Flow discipline rejects unsupported eager network work in a field initializer`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            fun prepare(): Flow<Unit> = flow { emit(Unit) }

            class Source {
                val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit> = prepare()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Eager network work during Flow property initialization")
    }

    @Test
    fun `latent Flow discipline validates a delegated this constructor argument`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Source(val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>) {
                constructor() : this(flow { emit(download()) })
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline validates a delegated super constructor argument`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            open class Base(val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>)

            class Derived : Base {
                constructor() : super(flow { emit(download()) })
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("not covered")
    }

    @Test
    fun `latent Flow discipline preserves flowOn producer work until collection`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.flowOn

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            fun stream() = flow { emit(download()) }.flowOn(Dispatchers.Default)

            fun buildOnly() { stream() }

            suspend fun load() { stream().collect() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for stream:"), result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(800, 1)}")
    }

    @Test
    fun `latent Flow discipline keeps effectful catch callbacks lazy and includes their work`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.catch
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            suspend fun download(): String = "content"

            @NetworkDownload(maxBytes = 850, completeTimeoutMillis = 1_000)
            suspend fun recover(): String = "fallback"

            fun stream() = flow { emit(download()) }.catch { emit(recover()) }

            fun buildOnly() { stream() }

            suspend fun load() { stream().collect() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for stream:"), result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(850, 1)}")
    }

    @Test
    fun `latent Flow discipline keeps effectful onCompletion callbacks lazy and includes their work`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.onCompletion

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun finish() = Unit

            fun stream() = flow { emit(download()) }.onCompletion { finish() }

            fun buildOnly() { stream() }

            suspend fun load() { stream().collect() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for stream:"), result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(900, 1)}")
    }

    @Test
    fun `latent Flow discipline activates a polymorphic Flow parameter through emitAll`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.emitAll
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 875, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            @BandwidthVariable("E")
            fun <T> forward(@BandwidthEffect("E") source: Flow<T>): Flow<T> = flow { emitAll(source) }

            fun buildOnly() { forward(flow { emit(download()) }) }

            suspend fun load() { forward(flow { emit(download()) }).collect() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for forward:"), result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(875, 1)}")
    }

    @Test
    fun `latent Flow discipline rejects emitAll of an unknown producer`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.emitAll
            import kotlinx.coroutines.flow.flow

            interface Boundary {
                fun stream(): Flow<Unit>
            }

            suspend fun load(boundary: Boundary) {
                flow { emitAll(boundary.stream()) }.collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("untracked Flow")
    }

    @Test
    fun `latent Flow discipline does not hide an unknown source behind catch or onCompletion`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.catch
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.onCompletion

            interface Boundary {
                fun stream(): Flow<Unit>
            }

            suspend fun load(boundary: Boundary) {
                boundary.stream().catch { emit(Unit) }.onCompletion { println("done") }.collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("untracked Flow")
    }

    @Test
    fun `static feasibility contracts do not classify a pure mapped List as a latent Flow payload`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.map

            fun rows(): List<Int> = listOf(1).map { it + 1 }

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            suspend fun download(): Int = 1

            suspend fun load() {
                flow { emit(download()) }.map { rows() }.collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
    }

    @Test
    fun `static feasibility contracts retain a nullable annotated source with an emptyFlow fallback`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.emptyFlow
            import kotlinx.coroutines.flow.map

            data class Row(val value: Int)

            suspend fun load(
                @BandwidthEffect(rMaxBytesPerSecond = 700, nMax = 1) source: Flow<Int>?,
            ) {
                (source?.map { Row(it) } ?: emptyFlow()).collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
    }

    @Test
    fun `static feasibility contracts keep Room query callbacks lazy and sequential`() {
        val result = compileSources(
            listOf(
                roomQueryFlowStub,
                """
                import androidx.room.coroutines.createFlow
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import kotlinx.coroutines.flow.toList

                @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
                fun download(): Int = 1

                fun stream() = createFlow(Unit, false, arrayOf("items")) { download() }
                fun buildOnly() { stream() }
                suspend fun load() { stream().toList() }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
    }

    @Test
    fun `static feasibility contracts reject unknown Room query callbacks`() {
        val result = compileSources(
            listOf(
                roomQueryFlowStub,
                """
                import androidx.room.coroutines.createFlow
                interface Boundary { fun query(): (Int) -> Int }
                fun build(boundary: Boundary) = createFlow(Unit, false, arrayOf("items"), boundary.query())
                """,
            ),
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer the Room createFlow query callback effect")
    }

    @Test
    fun `static feasibility contracts reject latent Room query result payloads`() {
        val result = compileSources(
            listOf(
                roomQueryFlowStub,
                """
                import androidx.room.coroutines.createFlow
                import kotlinx.coroutines.flow.flow
                fun build() = createFlow(Unit, false, arrayOf("items")) { flow { emit(1) } }
                """,
            ),
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Room createFlow cannot hide a latent Flow or function")
    }

    @Test
    fun `static feasibility contracts reject extra latent Room query inputs`() {
        val result = compileSources(
            listOf(
                roomQueryFlowStub,
                """
                import androidx.room.coroutines.createFlow
                fun build() = createFlow({ 1 }, false, arrayOf("items")) { 1 }
                """,
            ),
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Room createFlow does not support additional latent")
    }

    @Test
    fun `static feasibility contracts keep onStart lazy and before the producer`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.onStart

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            suspend fun begin() = Unit
            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            suspend fun download(): Int = 1

            fun stream() = flow { emit(download()) }.onStart { begin() }
            fun buildOnly() { stream() }
            suspend fun load() { stream().collect() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 1)}")
    }

    @Test
    fun `static feasibility contracts retain work escaping onStart across producer and later work`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.onStart
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            suspend fun background() = Unit
            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            suspend fun download(): Int = 1
            @NetworkDownload(maxBytes = 2000, completeTimeoutMillis = 1000)
            suspend fun after() = Unit

            suspend fun load(scope: CoroutineScope) {
                flow { emit(download()) }.onStart { scope.launch { background() } }.collect()
                after()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(2000, 2), (700, 2)}")
    }

    @Test
    fun `static feasibility contracts include withLock callbacks without inventing a shared bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.sync.Mutex
            import kotlinx.coroutines.sync.withLock

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            suspend fun download() = Unit

            suspend fun load(mutex: Mutex) = coroutineScope {
                launch { mutex.withLock { download() } }
                launch { mutex.withLock { download() } }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 2)}")
    }

    @Test
    fun `static feasibility contracts retain the original child when a mutable awaited handle changes`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            suspend fun firstDownload() = Unit

            @NetworkDownload(maxBytes = 2000, completeTimeoutMillis = 1000)
            suspend fun laterDownload() = Unit

            suspend fun load() = coroutineScope {
                var current = async { firstDownload() }
                current = async { Unit }
                current.await()
                laterDownload()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(2000, 2)}")
    }

    @Test
    fun `static feasibility contracts include an at most once cache initializer`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            fun download(): Int = 1

            fun load(cache: MutableMap<String, Int>) {
                cache.getOrPut("item") { download() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
    }

    @Test
    fun `static feasibility contracts await nullable mapped children and retain skipped prefix work`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            suspend fun download(value: Int): Int = value

            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            suspend fun prepare() = Unit

            @NetworkDownload(maxBytes = 2000, completeTimeoutMillis = 1000)
            suspend fun afterWait() = Unit

            suspend fun load(values: List<Any>) = coroutineScope {
                val slots = Semaphore(3)
                val results = values.mapNotNull { element ->
                    prepare()
                    if (element !is Int) return@mapNotNull null
                    val current = element
                    async { slots.withPermit { download(current) } }
                }.awaitAll()
                afterWait()
                results
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(2000, 1), (1000, 4)}")
    }

    @Test
    fun `static feasibility contracts reject a nullable map returning an alternate Deferred`() {
        val result = compile(
            """
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            suspend fun load(values: List<Int>) = coroutineScope {
                val existing = async { 0 }
                values.mapNotNull { value ->
                    if (value < 0) return@mapNotNull existing
                    async { value }
                }.awaitAll()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer awaitAll for an untracked Deferred collection")
    }

    @Test
    fun `static feasibility contracts reject a child started on a nullable map skip branch`() {
        val result = compile(
            """
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            suspend fun load(values: List<Int>) = coroutineScope {
                values.mapNotNull { value ->
                    if (value < 0) {
                        async { value }
                        return@mapNotNull null
                    }
                    async { value }
                }.awaitAll()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer awaitAll for an untracked Deferred collection")
    }

    @Test
    fun `static feasibility contracts accept owned guarded waits while retaining later overlap`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CancellationException
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            suspend fun download(): Int = 1

            @NetworkDownload(maxBytes = 2000, completeTimeoutMillis = 1000)
            suspend fun afterTry() = Unit

            suspend fun load() = coroutineScope {
                val first = async { download() }
                val second = async { download() }
                coroutineScope { Unit }
                try {
                    awaitAll(first, second)
                } catch (failure: CancellationException) {
                    throw failure
                }
                afterTry()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        // Guarded synchronization is accepted, but does not establish a common
        // continuation on every path. Both children remain conservatively live.
        result.assertOutputContains("Inferred bandwidth effect for load: {(2000, 3)}")
    }

    @Test
    fun `static feasibility contracts reject guarded waits containing an unowned parameter`() {
        val result = compile(
            """
            import kotlinx.coroutines.Deferred
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            suspend fun load(unowned: Deferred<Int>) = coroutineScope {
                val local = async { 1 }
                try {
                    awaitAll(local, unowned)
                } catch (failure: Exception) {
                    throw failure
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer awaitAll unless every argument")
    }

    @Test
    fun `static feasibility contracts do not lend structured ownership to a deferred Flow body`() {
        val result = compile(
            """
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            suspend fun load() = coroutineScope {
                val first = async { 1 }
                val second = async { 2 }
                val deferred = flow {
                    try {
                        awaitAll(first, second)
                    } catch (failure: Exception) {
                        throw failure
                    }
                    emit(Unit)
                }
                deferred.collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer awaitAll unless every argument")
    }

    @Test
    fun `static feasibility contracts keep completing repeat invocations sequential`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            fun download() = Unit

            fun load(count: Int) { repeat(count) { download() } }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
    }

    @Test
    fun `static feasibility contracts reject unbounded work escaping repeat invocations`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            suspend fun download() = Unit

            fun load(scope: CoroutineScope, count: Int) {
                repeat(count) { scope.launch { download() } }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work in repeated callback kotlin.repeat")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `static feasibility contracts materialize scalar map work at toList`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.map
            import kotlinx.coroutines.flow.toList

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            suspend fun download(value: Int): Int = value

            fun stream() = listOf(1, 2, 3).asFlow().map { download(it) }
            fun buildOnly() { stream() }
            suspend fun load() { stream().toList() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 1)}")
    }

    @Test
    fun `static feasibility contracts reject latent Flow payloads hidden by map`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.map
            import kotlinx.coroutines.flow.toList

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            suspend fun download(value: Int) = Unit

            suspend fun load() {
                listOf(1, 2).asFlow().map { value -> flow { emit(download(value)) } }.toList()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow map cannot hide a Flow or function value")
    }

    @Test
    fun `static feasibility contracts wait for supervisorScope children before subsequent work`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.supervisorScope

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            suspend fun download() = Unit

            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            suspend fun afterScope() = Unit

            suspend fun load() {
                supervisorScope {
                    launch { download() }
                    launch { download() }
                }
                afterScope()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 1), (700, 2)}")
    }

    @Test
    fun `static feasibility contracts invoke HttpStatement execute callback once within the call`() {
        val result = compileSources(
            sources = listOf(
                """
                package io.ktor.client.statement
                class HttpStatement {
                    suspend fun <T> execute(block: suspend (String) -> T): T = error("stub")
                }
                """,
                """
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import io.ktor.client.statement.HttpStatement

                @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
                suspend fun download() = Unit

                @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
                suspend fun afterCall() = Unit

                suspend fun load(statement: HttpStatement) {
                    statement.execute { download() }
                    afterCall()
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 1)}")
    }

    @Test
    fun `static feasibility contracts wait for each client WebSocket session callback`() {
        val result = compileSources(
            sources = listOf(
                """
                package io.ktor.client.plugins.websocket
                class Client
                suspend fun Client.webSocket(block: suspend () -> Unit) = Unit
                suspend fun Client.ws(block: suspend () -> Unit) = Unit
                suspend fun Client.wss(block: suspend () -> Unit) = Unit
                """,
                """
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import io.ktor.client.plugins.websocket.Client
                import io.ktor.client.plugins.websocket.webSocket
                import io.ktor.client.plugins.websocket.ws
                import io.ktor.client.plugins.websocket.wss

                @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
                suspend fun download() = Unit

                @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
                suspend fun afterCall() = Unit

                suspend fun load(client: Client) {
                    client.webSocket { download() }
                    client.ws { download() }
                    client.wss { download() }
                    afterCall()
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 1)}")
    }

    @Test
    fun `static feasibility contracts keep flatMapConcat inner collection lazy and sequential`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.ExperimentalCoroutinesApi
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.flatMapConcat
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.toList

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1000)
            suspend fun prepare(value: Int) = Unit

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1000)
            suspend fun download(value: Int): Int = value

            @OptIn(ExperimentalCoroutinesApi::class)
            fun stream() = listOf(1, 2, 3).asFlow().flatMapConcat { value ->
                prepare(value)
                flow { emit(download(value)) }
            }

            fun buildOnly() { stream() }
            suspend fun load() { stream().toList() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for stream:"), result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(900, 1)}")
    }

    @Test
    fun `static feasibility contracts overlap buffered producer with one concatenated inner flow`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.ExperimentalCoroutinesApi
            import kotlinx.coroutines.flow.buffer
            import kotlinx.coroutines.flow.flatMapConcat
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.toList

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1000)
            suspend fun nextItem(): Int = 1

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1000)
            suspend fun download(value: Int) = Unit

            @OptIn(ExperimentalCoroutinesApi::class)
            suspend fun load() {
                flow { emit(nextItem()) }.buffer(1)
                    .flatMapConcat { value -> flow { emit(download(value)) } }
                    .toList(mutableListOf())
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(900, 2)}")
    }

    @Test
    fun `static feasibility contracts reject an unknown concatenated inner Flow`() {
        val result = compile(
            """
            import kotlinx.coroutines.ExperimentalCoroutinesApi
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.flatMapConcat
            import kotlinx.coroutines.flow.toList

            interface Boundary { fun stream(): Flow<Int> }

            @OptIn(ExperimentalCoroutinesApi::class)
            suspend fun load(boundary: Boundary) {
                listOf(1, 2).asFlow().flatMapConcat { boundary.stream() }.toList()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow returned by flatMapConcat's transform")
    }

    @Test
    fun `static feasibility contracts reject work escaping repeated concatenated inner Flows`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.ExperimentalCoroutinesApi
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.flatMapConcat
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.toList
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1000)
            suspend fun download() = Unit

            @OptIn(ExperimentalCoroutinesApi::class)
            suspend fun load(scope: CoroutineScope) {
                listOf(1, 2).asFlow().flatMapConcat {
                    flow { scope.launch { download() }; emit(Unit) }
                }.toList()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work in flatMapConcat")
    }

    @Test
    fun `static feasibility contracts reject collecting an unknown Flow with toList`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.toList

            interface Boundary { fun stream(): Flow<Int> }
            suspend fun load(boundary: Boundary) { boundary.stream().toList() }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("toList on an untracked Flow")
    }

    @Test
    fun `static feasibility contracts synchronize direct immutable local awaitAll arguments`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
            suspend fun download(): Int = 1

            @NetworkDownload(maxBytes = 2000, completeTimeoutMillis = 1000)
            suspend fun afterWait() = Unit

            suspend fun load() = coroutineScope {
                val first = async { download() }
                val second = async { download() }
                val values = awaitAll(first, second).sorted()
                afterWait()
                values
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(2000, 1), (1000, 2)}")
    }

    @Test
    fun `static feasibility contracts reject awaitAll parameters as unowned child handles`() {
        val result = compile(
            """
            import kotlinx.coroutines.Deferred
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            suspend fun load(first: Deferred<Int>, second: Deferred<Int>) = coroutineScope {
                awaitAll(first, second)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer awaitAll unless every argument")
    }

    @Test
    fun `static feasibility contracts reject awaitAll of a reassigned mutable child handle`() {
        val result = compile(
            """
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            suspend fun load() = coroutineScope {
                var first = async { 1 }
                val second = async { 2 }
                first = async { 3 }
                awaitAll(first, second)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer awaitAll unless every argument")
    }

    @Test
    fun `static feasibility contracts keep filter callback effects lazy`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.filter
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun nextItem(): Int = 1

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            suspend fun accepts(value: Int): Boolean = true

            fun stream() = flow { emit(nextItem()) }.filter { accepts(it) }
            fun buildOnly() { stream() }
            suspend fun load() { stream().collect() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for stream:"), result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(700, 2)}")
    }

    @Test
    fun `static feasibility contracts include transform work without accumulating completed callbacks`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.transform

            @NetworkDownload(maxBytes = 650, completeTimeoutMillis = 1_000)
            suspend fun download(value: Int): Int = value

            fun stream() = listOf(1, 2, 3).asFlow().transform { emit(download(it)) }
            fun buildOnly() { stream() }
            suspend fun load() { stream().collect() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(650, 1)}")
    }

    @Test
    fun `static feasibility contracts include onEach and downstream collector effects`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.onEach

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            suspend fun inspect(value: Int) = Unit

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun save(value: Int) = Unit

            fun stream() = listOf(1, 2).asFlow().onEach { inspect(it) }
            fun buildOnly() { stream() }
            suspend fun load() { stream().collect { save(it) } }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(800, 2)}")
    }

    @Test
    fun `static feasibility contracts propagate producer and callback effect variables`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.onEach

            @BandwidthVariable("P", "C")
            fun <T> observe(
                @BandwidthEffect("P") source: Flow<T>,
                @BandwidthEffect("C") action: suspend (T) -> Unit,
            ): Flow<T> = source.onEach(action)

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun nextItem(): Int = 1

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun inspect(value: Int) = Unit

            suspend fun load() {
                observe(flow { emit(nextItem()) }) { inspect(it) }.collect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(900, 2)}")
    }

    @Test
    fun `static feasibility contracts preserve eager callback construction`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.onEach

            @NetworkDownload(maxBytes = 1200, completeTimeoutMillis = 1_000)
            suspend fun prepare() = Unit

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            suspend fun inspect(value: Int) = Unit

            suspend fun action(): suspend (Int) -> Unit {
                prepare()
                return { inspect(it) }
            }

            suspend fun buildOnly() { listOf(1, 2).asFlow().onEach(action()) }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for buildOnly: {(1200, 1)}")
    }

    @Test
    fun `static feasibility contracts reject work escaping unbounded per-element callbacks`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.onEach
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun download(value: Int) = Unit

            suspend fun load(scope: CoroutineScope) {
                listOf(1, 2).asFlow().onEach { scope.launch { download(it) } }.collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work in a Flow per-element callback")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `static feasibility contracts retain self bounded work escaping per-element callbacks`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.onEach
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download(value: Int) = Unit

            @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1_000)
            suspend fun afterCollect() = Unit

            suspend fun load(scope: CoroutineScope, @BoundedClient(k = 3) client: NetworkClient) {
                listOf(1, 2).asFlow().onEach { scope.launch { client.download(it) } }.collect()
                afterCollect()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 4), (700, 4)}")
    }

    @Test
    fun `static feasibility contracts do not hide an unknown source behind per-element operators`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.filter
            import kotlinx.coroutines.flow.onEach
            import kotlinx.coroutines.flow.transform

            interface Boundary { fun stream(): Flow<Int> }

            suspend fun load(boundary: Boundary) {
                boundary.stream().filter { true }.transform { emit(it) }.onEach { println(it) }.collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("per-element operator on an untracked Flow")
    }

    @Test
    fun `latent Flow discipline checks the receiver of an extension property setter`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            var Flow<Unit>.marker: Int
                get() = 0
                set(value) { println(value) }

            fun load() {
                val source = flow { emit(download()) }
                source.marker = 1
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow receiver")
    }

    @Test
    fun `latent Flow discipline rejects a bound Flow extension callable reference`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            @BandwidthEffect()
            suspend fun Flow<Unit>.consume() { collect() }

            suspend fun load() {
                val source = flow { emit(download()) }
                val action: suspend () -> Unit = source::consume
                action()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow extension callable reference")
    }

    @Test
    fun `latent Flow discipline rejects eager work in a virtual Flow accessor`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 350, completeTimeoutMillis = 1_000)
            fun prepare() = Unit

            open class Base {
                open val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>
                    get() = flow { emit(Unit) }
            }

            class Derived : Base() {
                override val stream: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>
                    get() {
                        prepare()
                        return flow { emit(Unit) }
                    }
            }

            fun readOnly(source: Base) { source.stream }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("virtual Flow accessor")
    }

    @Test
    fun `latent Flow discipline rejects eager network work in another Flow wrapper initializer`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.Flow

            @NetworkDownload(maxBytes = 300, completeTimeoutMillis = 1_000)
            fun downloadMetadata(): String = "metadata"

            class Wrapper(val source: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) Flow<Unit>) {
                val metadata: String = downloadMetadata()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow wrapper initializer")
    }

    @Test
    fun `latent Flow discipline tolerates Java ancestor types on an unrelated exception class`() {
        val result = compile(
            """
            class DownloadError(val code: Int) : RuntimeException() {
                val detail: String = "download failed"
            }

            fun makeFailure() = DownloadError(404)
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for makeFailure:"), result.output)
    }

    @Test
    fun `rejects flatMapMerge without an explicit constant concurrency bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.ExperimentalCoroutinesApi
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flatMapMerge
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            suspend fun download(url: String) = Unit

            @OptIn(ExperimentalCoroutinesApi::class)
            suspend fun load(urls: List<String>, limit: Int) {
                urls.asFlow()
                    .flatMapMerge(concurrency = limit) { url ->
                        flow { emit(download(url)) }
                    }
                    .collect()
            }

            @OptIn(ExperimentalCoroutinesApi::class)
            suspend fun loadWithDefault(urls: List<String>) {
                urls.asFlow()
                    .flatMapMerge { url ->
                        flow { emit(download(url)) }
                    }
                    .collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "flatMapMerge concurrency must be an explicit positive constant",
        )
    }

    @Test
    fun `keeps one shared Semaphore withPermit invocation local`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private const val MAX_SCORE_HANDLERS = 4
            private val scoreSlots = Semaphore(MAX_SCORE_HANDLERS)

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun downloadScores() = Unit

            suspend fun scoresHandler() = scoreSlots.withPermit {
                downloadScores()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for scoresHandler: {(1000, 1)}")
        result.assertOutputContains("ReqBW=1000 bytes/s")
    }

    @Test
    fun `uses a shared Semaphore bound across repeated launches`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private const val MAX_SCORE_HANDLERS = 4
            private val scoreSlots = Semaphore(MAX_SCORE_HANDLERS)

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun downloadScores() = Unit

            fun registerScoresHandlers(
                requests: List<Unit>,
                requestScope: CoroutineScope,
            ) {
                requests.forEach {
                    requestScope.launch {
                        scoreSlots.withPermit { downloadScores() }
                    }
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for registerScoresHandlers: {(1000, 4)}",
        )
        result.assertOutputContains("ReqBW=4000 bytes/s")
    }

    @Test
    fun `scales a shared Semaphore bound by internal fanout`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private val scoreSlots = Semaphore(4)

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun downloadScores() = Unit

            fun registerScoresHandlers(
                requests: List<Unit>,
                requestScope: CoroutineScope,
            ) {
                requests.forEach {
                    requestScope.launch {
                        scoreSlots.withPermit {
                            coroutineScope {
                                launch { downloadScores() }
                                launch { downloadScores() }
                            }
                        }
                    }
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for registerScoresHandlers: {(1000, 8)}",
        )
        result.assertOutputContains("ReqBW=8000 bytes/s")
    }

    @Test
    fun `keeps a local Semaphore transparent for one invocation`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun downloadScores() = Unit

            suspend fun scoresHandler() {
                val scoreSlots = Semaphore(4)
                scoreSlots.withPermit { downloadScores() }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for scoresHandler: {(1000, 1)}")
    }

    @Test
    fun `bounds a local Semaphore batch without exporting a shared self bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun download(value: Int) = value

            suspend fun batch(values: List<Int>) = coroutineScope {
                val batchSlots = Semaphore(3)
                data class Result(val index: Int)
                val jobs = values.map { value ->
                    val index = value + 1
                    async {
                        val toolIndex = index + 1
                        println("queue " + toolIndex)
                        batchSlots.withPermit {
                            println("active " + (3 - batchSlots.availablePermits))
                            try {
                                Result(download(toolIndex))
                            } finally {
                                println("finish " + batchSlots.availablePermits)
                            }
                        }
                    }
                }
                val results = jobs.awaitAll().sortedBy { it.index }
                results.size
            }

            suspend fun twoBatches(values: List<Int>) = coroutineScope {
                async { batch(values) }
                async { batch(values) }
            }

            suspend fun threeBatches(values: List<Int>) = coroutineScope {
                async { batch(values) }
                async { batch(values) }
                async { batch(values) }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for batch: {(1000, 3)}")
        result.assertOutputContains("Inferred bandwidth effect for twoBatches: {(1000, 6)}")
        result.assertOutputContains("Inferred bandwidth effect for threeBatches: {(1000, 9)}")
    }

    @Test
    fun `multiplies a local Semaphore batch by the shared handler gate`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            val handlerSlots = Semaphore(3)

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun batch(values: List<Int>) = coroutineScope {
                val batchSlots = Semaphore(3)
                values.map { async { batchSlots.withPermit { download() } } }.awaitAll()
            }

            fun register(requests: List<List<Int>>, scope: CoroutineScope) {
                requests.forEach { values ->
                    scope.launch { handlerSlots.withPermit { batch(values) } }
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for register: {(1000, 9)}")
    }

    @Test
    fun `preserves internal fanout in a local Semaphore batch`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun batch(values: List<Int>) = coroutineScope {
                val batchSlots = Semaphore(3)
                values.map {
                    async {
                        batchSlots.withPermit {
                            coroutineScope {
                                launch { download() }
                                launch { download() }
                            }
                        }
                    }
                }.awaitAll()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for batch: {(1000, 6)}")
    }

    @Test
    fun `preserves preparation work overlapping a local Semaphore batch`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun batch(values: List<Int>) = coroutineScope {
                val batchSlots = Semaphore(3)
                val jobs = values.map {
                    download()
                    async { batchSlots.withPermit { download() } }
                }
                jobs.awaitAll()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for batch: {(1000, 4)}")
    }

    @Test
    fun `rejects an allocation per child as a local Semaphore batch bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun batch(values: List<Int>) = coroutineScope {
                values.map {
                    val batchSlots = Semaphore(3)
                    async { batchSlots.withPermit { download() } }
                }.awaitAll()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Mapped async children may overlap")
    }

    @Test
    fun `rejects unsupported resources as a local Semaphore batch bound`() {
        val resourceSetups = listOf(
            "val batchSlots = Semaphore(limit)",
            "var batchSlots = Semaphore(3)",
            "val original = Semaphore(3); val batchSlots = original",
            "val batchSlots = Semaphore(3); val alias = batchSlots",
            "val batchSlots = Semaphore(3); batchSlots.release()",
        )
        resourceSetups.forEach { setup ->
            val result = compile(
                """
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import kotlinx.coroutines.async
                import kotlinx.coroutines.awaitAll
                import kotlinx.coroutines.coroutineScope
                import kotlinx.coroutines.sync.Semaphore
                import kotlinx.coroutines.sync.withPermit

                @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
                suspend fun download() = Unit

                suspend fun batch(values: List<Int>, limit: Int) = coroutineScope {
                    $setup
                    values.map { async { batchSlots.withPermit { download() } } }.awaitAll()
                }
                """,
            )
            assertNotEquals(0, result.exitCode, "$setup\n${result.output}")
            result.assertOutputContains("Mapped async children may overlap")
        }
    }

    @Test
    fun `rejects unprotected child work in a local Semaphore batch`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun batch(values: List<Int>) = coroutineScope {
                val batchSlots = Semaphore(3)
                values.map {
                    async {
                        download()
                        batchSlots.withPermit { download() }
                    }
                }.awaitAll()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Mapped async children may overlap")
    }

    @Test
    fun `rejects escaping work in a local Semaphore batch`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun batch(values: List<Int>, external: CoroutineScope) = coroutineScope {
                val batchSlots = Semaphore(3)
                values.map {
                    async {
                        batchSlots.withPermit { external.launch { download() } }
                    }
                }.awaitAll()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("network work that may outlive that child")
    }

    @Test
    fun `treats repeated launches without a known Semaphore bound as unbounded`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private val configuredLimit = System.getProperty("score.limit", "4").toInt()
            private val scoreSlots = Semaphore(configuredLimit)

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun downloadScores() = Unit

            fun registerScoresHandlers(
                requests: List<Unit>,
                requestScope: CoroutineScope,
            ) {
                requests.forEach {
                    requestScope.launch {
                        scoreSlots.withPermit { downloadScores() }
                    }
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work in repeated callback")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `allows unbounded repeated launches when the body has no network effect`() {
        val result = compile(
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            fun registerHandlers(
                requests: List<Unit>,
                requestScope: CoroutineScope,
            ) {
                requests.forEach {
                    requestScope.launch { println("handled") }
                }
            }
            """,
        )

        assertEquals(0, result.exitCode, result.output)
    }

    @Test
    fun `does not apply a Semaphore bound to one escaping launch`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private val scoreSlots = Semaphore(4)

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun downloadScores() = Unit

            suspend fun scoresHandler(requestScope: CoroutineScope) =
                scoreSlots.withPermit {
                    requestScope.launch { downloadScores() }
                }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for scoresHandler: {(1000, 1)}")
    }

    @Test
    fun `rejects repeated work escaping a Semaphore permit`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private val scoreSlots = Semaphore(4)

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun downloadScores() = Unit

            suspend fun registerScoresHandlers(
                requests: List<Unit>,
                requestScope: CoroutineScope,
            ) {
                requests.forEach {
                    scoreSlots.withPermit {
                        requestScope.launch { downloadScores() }
                    }
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work in repeated callback")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `composes otherwise uncalled entry points once in parallel`() {
        val result = compileSources(
            sources = listOf(
                """
                import io.github.loinguyen.bandwidth.annotations.EntryPoint
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload

                @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
                suspend fun download() = Unit

                @EntryPoint
                suspend fun firstRoot() {
                    download()
                }
                """,
                """
                import io.github.loinguyen.bandwidth.annotations.EntryPoint

                @EntryPoint
                suspend fun secondRoot() {
                    download()
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 2 entry point(s): {(1000, 2)}",
        )
        result.assertOutputContains("ReqBW=2000 bytes/s")
    }

    @Test
    fun `reports independent maxima alongside correlated application bandwidth`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.EntryPoint
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 10, completeTimeoutMillis = 1_000)
            suspend fun highRate() = Unit

            @NetworkDownload(maxBytes = 1, completeTimeoutMillis = 1_000)
            suspend fun lowRate() = Unit

            @EntryPoint
            suspend fun application() {
                highRate()
                coroutineScope {
                    awaitAll(async { lowRate() }, async { lowRate() }, async { lowRate() })
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(10, 1), (1, 3)}, " +
                "ReqBW=10 bytes/s. IndependentMaximaBW=30 bytes/s (rMax=10 bytes/s, nMax=3).",
        )
    }

    @Test
    fun `static feasibility contracts preserve handlers registered inside a route builder`() {
        val result = compileSources(
            sources = listOf(
                ktorRoutingStub,
                """
                import io.github.loinguyen.bandwidth.annotations.EntryPoint
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import io.ktor.server.routing.Application
                import io.ktor.server.routing.get
                import io.ktor.server.routing.route
                import io.ktor.server.routing.routing
                import kotlinx.coroutines.sync.Semaphore
                import kotlinx.coroutines.sync.withPermit

                private val slots = Semaphore(3)

                @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
                suspend fun download() = Unit

                @EntryPoint
                fun Application.module() {
                    routing {
                        route("/api") {
                            get("/picture") { slots.withPermit { download() } }
                        }
                    }
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(1000, 3)}",
        )
    }

    @Test
    fun `static feasibility contracts reject an unbounded network WebSocket handler`() {
        val result = compileSources(
            sources = listOf(
                ktorRoutingStub,
                ktorWebSocketStub,
                """
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import io.ktor.server.routing.Application
                import io.ktor.server.routing.routing
                import io.ktor.server.websocket.webSocketRaw

                @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
                suspend fun download() = Unit

                fun Application.module() {
                    routing { webSocketRaw("/events") { download() } }
                }
                """,
            ),
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("io.ktor.server.websocket.webSocketRaw")
        result.assertOutputContains("self bound")
    }

    @Test
    fun `static feasibility contracts bound concurrent WebSocket handlers by a persistent gate`() {
        val result = compileSources(
            sources = listOf(
                ktorRoutingStub,
                ktorWebSocketStub,
                """
                import io.github.loinguyen.bandwidth.annotations.EntryPoint
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import io.ktor.server.routing.Application
                import io.ktor.server.routing.routing
                import io.ktor.server.websocket.webSocketRaw
                import kotlinx.coroutines.sync.Semaphore
                import kotlinx.coroutines.sync.withPermit

                private val slots = Semaphore(3)

                @NetworkDownload(maxBytes = 1000, completeTimeoutMillis = 1000)
                suspend fun download() = Unit

                @EntryPoint
                fun Application.module() {
                    routing { webSocketRaw("/events") { slots.withPermit { download() } } }
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(1000, 3)}",
        )
    }

    @Test
    fun `composes independently bounded Ktor get handlers in parallel`() {
        val result = compileSources(
            sources = listOf(
                ktorRoutingStub,
                """
                import io.github.loinguyen.bandwidth.annotations.EntryPoint
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import io.ktor.server.routing.Application
                import io.ktor.server.routing.get
                import io.ktor.server.routing.routing
                import kotlinx.coroutines.sync.Semaphore
                import kotlinx.coroutines.sync.withPermit

                private val pictureSlots = Semaphore(3)
                private val searchSlots = Semaphore(5)

                @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
                suspend fun download() = Unit

                @EntryPoint
                fun Application.module() {
                    routing {
                        get("/picture") {
                            pictureSlots.withPermit { download() }
                        }
                        get("/search") {
                            searchSlots.withPermit { download() }
                        }
                    }
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(1000, 8)}",
        )
        result.assertOutputContains("ReqBW=8000 bytes/s")
    }

    @Test
    fun `models BeatSaver resource and Swagger get handlers`() {
        val result = compileSources(
            sources = listOf(
                ktorRoutingStub,
                ktorResourcesStub,
                ktorSwaggerStub,
                """
                import de.nielsfalk.ktor.swagger.Metadata
                import de.nielsfalk.ktor.swagger.get as swaggerGet
                import io.github.loinguyen.bandwidth.annotations.EntryPoint
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import io.ktor.server.resources.get as resourceGet
                import io.ktor.server.routing.Application
                import io.ktor.server.routing.routing
                import kotlinx.coroutines.sync.Semaphore
                import kotlinx.coroutines.sync.withPermit

                class PictureResource
                class SearchResource

                private val pictureSlots = Semaphore(2)
                private val searchSlots = Semaphore(3)

                @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
                suspend fun download() = Unit

                @EntryPoint
                fun Application.module() {
                    routing {
                        resourceGet<PictureResource> { _ ->
                            pictureSlots.withPermit { download() }
                        }
                        swaggerGet<SearchResource>(Metadata()) { _ ->
                            searchSlots.withPermit { download() }
                        }
                    }
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(1000, 5)}",
        )
        result.assertOutputContains("ReqBW=5000 bytes/s")
    }

    @Test
    fun `models Ktor post and BeatSaver route helper handlers`() {
        val result = compileSources(
            sources = listOf(
                ktorRoutingStub,
                ktorResourcesStub,
                """
                package io.beatmaps.api.util

                import io.ktor.server.routing.Route
                import io.ktor.server.routing.RoutingContext

                inline fun <reified T : Any> Route.getWithOptions(
                    noinline body: suspend RoutingContext.(T) -> Unit,
                ) = Unit
                """,
                """
                import io.beatmaps.api.util.getWithOptions
                import io.github.loinguyen.bandwidth.annotations.EntryPoint
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import io.ktor.server.resources.post
                import io.ktor.server.routing.Application
                import io.ktor.server.routing.routing
                import kotlinx.coroutines.sync.Semaphore
                import kotlinx.coroutines.sync.withPermit

                class CreateResource
                class ReadResource

                private val createSlots = Semaphore(2)
                private val readSlots = Semaphore(3)

                @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
                suspend fun download() = Unit

                @EntryPoint
                fun Application.module() {
                    routing {
                        post<CreateResource> { _ ->
                            createSlots.withPermit { download() }
                        }
                        getWithOptions<ReadResource> { _ ->
                            readSlots.withPermit { download() }
                        }
                    }
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(1000, 5)}",
        )
        result.assertOutputContains("ReqBW=5000 bytes/s")
    }

    @Test
    fun `models embeddedServer module as a single callback`() {
        val result = compileSources(
            sources = listOf(
                ktorRoutingStub,
                """
                package io.ktor.server.engine

                import io.ktor.server.routing.Application

                class ApplicationEngine

                fun embeddedServer(module: Application.() -> Unit) = ApplicationEngine()
                """,
                """
                import io.github.loinguyen.bandwidth.annotations.EntryPoint
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import io.ktor.server.engine.embeddedServer
                import io.ktor.server.routing.get
                import io.ktor.server.routing.routing
                import kotlinx.coroutines.sync.Semaphore
                import kotlinx.coroutines.sync.withPermit

                private val slots = Semaphore(2)

                @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
                suspend fun download() = Unit

                @EntryPoint
                fun main() {
                    embeddedServer {
                        routing {
                            get("/picture") {
                                slots.withPermit { download() }
                            }
                        }
                    }
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(1000, 2)}",
        )
        result.assertOutputContains("ReqBW=2000 bytes/s")
    }

    @Test
    fun `rejects a Ktor get handler without a finite bound`() {
        val result = compileSources(
            sources = listOf(
                ktorRoutingStub,
                """
                import io.github.loinguyen.bandwidth.annotations.EntryPoint
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import io.ktor.server.routing.Application
                import io.ktor.server.routing.get
                import io.ktor.server.routing.routing

                @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
                suspend fun download() = Unit

                @EntryPoint
                fun Application.module() {
                    routing {
                        get("/picture") { download() }
                    }
                }
                """,
            ),
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Network work in concurrent repeated callback")
        result.assertOutputContains("io.ktor.server.routing.get")
    }

    @Test
    fun `invokes a Ktor routing configuration only once`() {
        val result = compileSources(
            sources = listOf(
                ktorRoutingStub,
                """
                import io.github.loinguyen.bandwidth.annotations.EntryPoint
                import io.github.loinguyen.bandwidth.annotations.NetworkDownload
                import io.ktor.server.routing.Application
                import io.ktor.server.routing.routing

                @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
                fun startupDownload() = Unit

                @EntryPoint
                fun Application.module() {
                    routing { startupDownload() }
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {(1000, 1)}",
        )
        result.assertOutputContains("ReqBW=1000 bytes/s")
    }

    @Test
    fun `allows an unbounded Ktor get handler with no network effect`() {
        val result = compileSources(
            sources = listOf(
                ktorRoutingStub,
                """
                import io.github.loinguyen.bandwidth.annotations.EntryPoint
                import io.ktor.server.routing.Application
                import io.ktor.server.routing.get
                import io.ktor.server.routing.routing

                @EntryPoint
                fun Application.module() {
                    routing {
                        get("/health") { println("healthy") }
                    }
                }
                """,
            ),
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred application entry-point effect from 1 entry point(s): {}",
        )
        result.assertOutputContains("ReqBW=0 bytes/s")
    }

    @Test
    fun `uses an interface contract through an NIA style implementation`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            interface NiaNetworkDataSource {
                @BandwidthEffect(rMaxBytesPerSecond = 900, nMax = 1)
                suspend fun getNewsResources(): List<String>
            }

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            suspend fun retrofitGetNewsResources(): List<String> = emptyList()

            class RetrofitNiaNetwork : NiaNetworkDataSource {
                override suspend fun getNewsResources(): List<String> =
                    retrofitGetNewsResources()
            }

            suspend fun sync(network: NiaNetworkDataSource) {
                network.getNewsResources()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for sync: {(900, 1)}")
    }

    @Test
    fun `uses an interface contract through a Dagger bound NIA style dependency`() {
        val result = compile(
            """
            import dagger.Binds
            import dagger.Module
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import javax.inject.Inject

            interface NiaNetworkDataSource {
                @BandwidthEffect(rMaxBytesPerSecond = 900, nMax = 1)
                suspend fun getNewsResources(): List<String>
            }

            @NetworkDownload(maxBytes = 800, completeTimeoutMillis = 1_000)
            suspend fun retrofitGetNewsResources(): List<String> = emptyList()

            class RetrofitNiaNetwork @Inject constructor() : NiaNetworkDataSource {
                override suspend fun getNewsResources(): List<String> =
                    retrofitGetNewsResources()
            }

            @Module
            interface NetworkModule {
                @Binds
                fun bindNetwork(implementation: RetrofitNiaNetwork): NiaNetworkDataSource
            }

            class SyncWorker @Inject constructor(
                private val network: NiaNetworkDataSource,
            ) {
                suspend fun doWork() {
                    network.getNewsResources()
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for SyncWorker.doWork: {(900, 1)}")
    }

    @Test
    fun `rejects an NIA style implementation exceeding its interface contract`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            interface NiaNetworkDataSource {
                @BandwidthEffect(rMaxBytesPerSecond = 900, nMax = 1)
                suspend fun getNewsResources(): List<String>
            }

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun retrofitGetNewsResources(): List<String> = emptyList()

            class RetrofitNiaNetwork : NiaNetworkDataSource {
                override suspend fun getNewsResources(): List<String> =
                    retrofitGetNewsResources()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred override effect {(1000, 1)} is not covered")
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
    fun `treats an unannotated opaque callback return as non-network`() {
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

        assertEquals(0, result.exitCode, result.output)
    }

    @Test
    fun `treats an unannotated higher-order parameter as non-network`() {
        val result = compile(
            """
            fun invokeNetwork(callback: () -> Unit) {
                callback()
            }
            """,
        )

        assertEquals(0, result.exitCode, result.output)
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
    fun `rejects network work passed to an explicitly empty callback parameter`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            fun invokeEmpty(@BandwidthEffect() callback: () -> Unit) {
                callback()
            }

            fun caller() {
                invokeEmpty { primitive() }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Higher-order argument effect {(600, 1)} is not covered")
        result.assertOutputContains("@BandwidthEffect contract {}")
    }

    @Test
    fun `does not silently discard an effectful external higher-order argument`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            fun primitive() = Unit

            fun deferredSequence() = sequenceOf(1).map {
                    primitive()
                    it
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Effectful higher-order argument for parameter 'transform'")
    }

    @Test
    fun `does not require a contract for an omitted default callback`() {
        val result = compile(
            """
            fun configure(
                value: Int,
                callback: () -> Unit = {},
            ) = value

            fun caller() {
                configure(1)
            }
            """,
        )

        assertEquals(0, result.exitCode, result.output)
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
    fun `infers completing network work in general loops`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            fun primary() = Unit

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            fun secondary() = Unit

            fun whileLoop(count: Int) {
                var index = 0
                while (index < count) {
                    primary()
                    index += 1
                }
            }

            fun doWhileLoop(count: Int) {
                var index = 0
                do {
                    secondary()
                    index += 1
                } while (index < count)
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for whileLoop: {(1000, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for doWhileLoop: {(600, 1)}")
    }

    @Test
    fun `models bounded escaping work across loop iterations`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthDownload
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            interface NetworkLibrary {
                @BandwidthEffect(
                    downloads = [
                        BandwidthDownload(
                            rMaxBytesPerSecond = 700,
                            nMax = 1,
                            selfBound = 3,
                            mayOutliveCall = true,
                        ),
                    ],
                )
                fun startDownload()
            }

            fun repeated(count: Int, library: NetworkLibrary) {
                var index = 0
                while (index < count) {
                    library.startDownload()
                    index += 1
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for repeated: {(700, 3)}")
        result.assertOutputContains("ReqBW=2100 bytes/s")
    }

    @Test
    fun `rejects unbounded escaping work across loop iterations`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthDownload
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            interface NetworkLibrary {
                @BandwidthEffect(
                    downloads = [
                        BandwidthDownload(
                            rMaxBytesPerSecond = 700,
                            nMax = 1,
                            mayOutliveCall = true,
                        ),
                    ],
                )
                fun startDownload()
            }

            fun repeated(count: Int, library: NetworkLibrary) {
                var index = 0
                while (index < count) {
                    library.startDownload()
                    index += 1
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Escaping network work in a general loop may overlap across an unknown " +
                "number of iterations",
        )
        result.assertOutputContains(
            "Use a self bound for every download",
        )
    }

    @Test
    fun `keeps sequential downloads inside one external launch sequential`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            fun load(
                scope: CoroutineScope,
                @BoundedClient(k = 2) firstClient: NetworkClient,
                @BoundedClient(k = 2) secondClient: NetworkClient,
            ) {
                scope.launch {
                    firstClient.download()
                    secondClient.download()
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 1)}")
        result.assertOutputContains("ReqBW=1000 bytes/s")
    }

    @Test
    fun `awaitAll overlaps an earlier child with later callback evaluation`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.coroutineScope

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun childDownload() = Unit

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun callbackInputDownload() = Unit

            suspend fun callbackFactory(): suspend CoroutineScope.() -> Unit {
                callbackInputDownload()
                return {}
            }

            suspend fun load() = coroutineScope {
                awaitAll(
                    async { childDownload() },
                    async(block = callbackFactory()),
                )
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 2)}")
        result.assertOutputContains("ReqBW=2000 bytes/s")
    }

    @Test
    fun `annotated escaping lifetime survives an interprocedural call`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthDownload
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.backgroundDownload() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun foregroundDownload() = Unit

            @BandwidthEffect(
                downloads = [
                    BandwidthDownload(
                        rMaxBytesPerSecond = 1_000,
                        nMax = 2,
                        mayOutliveCall = true,
                    ),
                ],
            )
            fun start(
                scope: CoroutineScope,
                @BoundedClient(k = 2) client: NetworkClient,
            ) {
                scope.launch { client.backgroundDownload() }
            }

            suspend fun load(scope: CoroutineScope, client: NetworkClient) {
                start(scope, client)
                foregroundDownload()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 3)}")
        result.assertOutputContains("ReqBW=3000 bytes/s")
    }

    @Test
    fun `preserves an opaque self bound across unknown repetition`() {
        val result = compile(
            """
            package androidx.compose.material3

            import io.github.loinguyen.bandwidth.annotations.BandwidthDownload
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            fun Button(
                onClick: () -> Unit,
                content: () -> Unit,
            ) = Unit

            interface ImageLibrary {
                @BandwidthEffect(
                    downloads = [
                        BandwidthDownload(
                            rMaxBytesPerSecond = 500_000,
                            nMax = 1,
                            mayOutliveCall = true,
                            selfBound = 4,
                        ),
                    ],
                )
                fun startImageDownload(url: String)
            }

            fun DownloadButton(library: ImageLibrary, url: String) {
                Button(
                    onClick = { library.startImageDownload(url) },
                    content = {},
                )
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Inferred bandwidth effect for " +
                "androidx.compose.material3.DownloadButton: {(500000, 4)}",
        )
        result.assertOutputContains("ReqBW=2000000 bytes/s")
    }

    @Test
    fun `rejects a negative opaque self bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthDownload
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect

            @BandwidthEffect(
                downloads = [
                    BandwidthDownload(
                        rMaxBytesPerSecond = 500_000,
                        nMax = 1,
                        selfBound = -1,
                    ),
                ],
            )
            fun load() = Unit
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "BandwidthDownload selfBound must be a non-negative constant",
        )
    }

    @Test
    fun `rejects a completing contract for visible escaping work`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthDownload
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.download() = Unit

            @BandwidthEffect(
                downloads = [
                    BandwidthDownload(
                        rMaxBytesPerSecond = 1_000,
                        nMax = 2,
                    ),
                ],
            )
            fun start(
                scope: CoroutineScope,
                @BoundedClient(k = 2) client: NetworkClient,
            ) {
                scope.launch { client.download() }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("is not covered by @BandwidthEffect contract")
    }

    @Test
    fun `try and finally escaping jobs remain mutually parallel`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BoundedClient
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch

            class NetworkClient

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.first() = Unit

            @NetworkDownload(maxBytes = 500, completeTimeoutMillis = 1_000)
            suspend fun NetworkClient.second() = Unit

            fun load(
                scope: CoroutineScope,
                @BoundedClient(k = 2) firstClient: NetworkClient,
                @BoundedClient(k = 2) secondClient: NetworkClient,
            ) {
                try {
                    scope.launch { firstClient.first() }
                } finally {
                    scope.launch { secondClient.second() }
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 2)}")
        result.assertOutputContains("ReqBW=2000 bytes/s")
    }

    @Test
    fun `state flow contracts preserve a local configuration property through asStateFlow`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.StateFlow
            import kotlinx.coroutines.flow.asStateFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.filterNotNull

            class App {
                private val current = MutableStateFlow<String?>(null)
                val config: StateFlow<String?> = current.asStateFlow()
            }

            suspend fun observe(app: App) {
                app.config.filterNotNull().collect { it.length }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for observe:"), result.output)
    }

    @Test
    fun `state flow contracts charge initial value evaluation once rather than at every observation`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.collect

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            fun initialValue() = 1

            fun buildOnly() { MutableStateFlow(initialValue()) }

            suspend fun observe() {
                val state = MutableStateFlow(initialValue())
                state.collect { it + 1 }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for buildOnly: {(700, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for observe: {(700, 1)}")
    }

    @Test
    fun `state flow contracts keep effectful observer actions latent until collection`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.asStateFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.onEach

            @NetworkDownload(maxBytes = 750, completeTimeoutMillis = 1_000)
            suspend fun download(value: Int) = Unit

            fun events() = MutableStateFlow(1).asStateFlow().onEach { download(it) }

            fun buildOnly() { events() }

            suspend fun observe() { events().collect() }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for observe: {(750, 1)}")
    }

    @Test
    fun `state flow contracts materialize explicit StateFlow and MutableStateFlow parameter effects`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.StateFlow
            import kotlinx.coroutines.flow.collect

            typealias States<T> = StateFlow<T>

            interface Boundary {
                fun states(): @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) StateFlow<Int>
                fun mutableStates(): @BandwidthEffect(rMaxBytesPerSecond = 600, nMax = 1) MutableStateFlow<Int>
            }

            suspend fun consume(source: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) States<Int>) {
                source.collect { }
            }

            suspend fun consumeMutable(source: @BandwidthEffect(rMaxBytesPerSecond = 600, nMax = 1) MutableStateFlow<Int>) {
                source.collect { }
            }

            suspend fun load(boundary: Boundary) { consume(boundary.states()) }
            suspend fun loadMutable(boundary: Boundary) { consumeMutable(boundary.mutableStates()) }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(800, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for loadMutable: {(600, 1)}")
    }

    @Test
    fun `state flow contracts substitute a polymorphic subtype parameter and returned effect`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.StateFlow
            import kotlinx.coroutines.flow.collect

            interface CustomFlow<out T> : Flow<T>

            interface Boundary {
                fun states(): @BandwidthEffect(rMaxBytesPerSecond = 850, nMax = 1) StateFlow<Int>
                fun custom(): @BandwidthEffect(rMaxBytesPerSecond = 650, nMax = 1) CustomFlow<Int>
            }

            @BandwidthVariable("E")
            fun <T> forward(@BandwidthEffect("E") source: StateFlow<T>): StateFlow<T> = source

            @BandwidthVariable("E")
            fun <T> forwardCustom(@BandwidthEffect("E") source: CustomFlow<T>): CustomFlow<T> = source

            suspend fun load(boundary: Boundary) { forward(boundary.states()).collect { } }
            suspend fun loadCustom(boundary: Boundary) { forwardCustom(boundary.custom()).collect { } }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for forward:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(850, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for loadCustom: {(650, 1)}")
    }

    @Test
    fun `state flow contracts reject an opaque StateFlow hidden behind a property getter`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.StateFlow
            import kotlinx.coroutines.flow.collect

            interface Boundary { fun states(): StateFlow<Int> }

            class Source(private val boundary: Boundary) {
                val states: StateFlow<Int> get() = boundary.states()
            }

            suspend fun observe(source: Source) { source.states.collect { } }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("untracked Flow")
    }

    @Test
    fun `state flow contracts do not launder an opaque subtype through an empty parameter contract`() {
        val result = compile(
            """
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.StateFlow
            import kotlinx.coroutines.flow.asStateFlow
            import kotlinx.coroutines.flow.collect

            interface Boundary { fun states(): MutableStateFlow<Int> }

            suspend fun consume(source: StateFlow<Int>) { source.collect { } }
            suspend fun load(boundary: Boundary) { consume(boundary.states().asStateFlow()) }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("untracked Flow")
    }

    @Test
    fun `state flow contracts check inherited StateFlow property effect bounds`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.StateFlow

            open class Base {
                open val states: @BandwidthEffect(rMaxBytesPerSecond = 800, nMax = 1) StateFlow<Int> =
                    MutableStateFlow(1)
            }

            class Child : Base() {
                override val states: @BandwidthEffect(rMaxBytesPerSecond = 900, nMax = 1) StateFlow<Int> =
                    MutableStateFlow(1)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("not covered")
    }

    @Test
    fun `state flow contracts retain receiveAsFlow collector downloads from a local channel property`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.channels.Channel
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.onEach
            import kotlinx.coroutines.flow.receiveAsFlow

            @NetworkDownload(maxBytes = 900, completeTimeoutMillis = 1_000)
            suspend fun download(value: Int) = Unit

            class Events {
                private val channel = Channel<Int>(4)
                val values = channel.receiveAsFlow()
            }

            fun buildOnly(events: Events) { events.values.onEach { download(it) } }
            suspend fun observe(events: Events) { events.values.collect { download(it) } }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        assertFalse(result.output.contains("Inferred bandwidth effect for buildOnly:"), result.output)
        result.assertOutputContains("Inferred bandwidth effect for observe: {(900, 1)}")
    }

    @Test
    fun `state flow contracts reject receiveAsFlow after a local channel is reassigned an opaque channel`() {
        val result = compile(
            """
            import kotlinx.coroutines.channels.Channel
            import kotlinx.coroutines.channels.ReceiveChannel
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.receiveAsFlow

            interface Boundary { fun channel(): ReceiveChannel<Int> }

            suspend fun observe(boundary: Boundary) {
                var channel: ReceiveChannel<Int> = Channel<Int>(4)
                channel = boundary.channel()
                channel.receiveAsFlow().collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("untracked")
    }

    @Test
    fun `state flow contracts do not treat stateIn as a local empty state factory`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.SharingStarted
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.stateIn

            @NetworkDownload(maxBytes = 950, completeTimeoutMillis = 1_000)
            suspend fun download() = 1

            suspend fun observe(scope: CoroutineScope) {
                flow { emit(download()) }.stateIn(scope, SharingStarted.Eagerly, 0).collect { }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow receiver effect {(950, 1)} is not covered by its contract {} on kotlinx.coroutines.flow.stateIn")
    }

    @Test
    fun `state flow contracts do not treat shareIn as an empty local signaling source`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.SharingStarted
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.shareIn

            @NetworkDownload(maxBytes = 950, completeTimeoutMillis = 1_000)
            suspend fun download() = 1

            suspend fun observe(scope: CoroutineScope) {
                flow { emit(download()) }.shareIn(scope, SharingStarted.Eagerly).collect { }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Flow receiver effect {(950, 1)} is not covered by its contract {} on kotlinx.coroutines.flow.shareIn")
    }

    @Test
    fun `state flow contracts preserve upstream work through distinctUntilChanged and buffer`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.buffer
            import kotlinx.coroutines.flow.collectLatest
            import kotlinx.coroutines.flow.distinctUntilChanged
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun download() = 1

            suspend fun observe() {
                flow { emit(download()) }.distinctUntilChanged().buffer(1).collectLatest { it + 1 }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for observe: {(1000, 1)}")
    }

    @Test
    fun `state flow contracts do not discard an effectful distinctUntilChanged comparator`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.flow.distinctUntilChanged

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            fun equivalent(previous: Int, current: Int) = false

            suspend fun observe() {
                listOf(1, 2).asFlow().distinctUntilChanged { previous, current ->
                    equivalent(previous, current)
                }.collect()
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("areEquivalent")
    }

    @Test
    fun `state flow contracts preserve a pure lazy cancellation state and its observer callback`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.collect

            @NetworkDownload(maxBytes = 1_200, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            class Worker {
                private val isCancelled by lazy { MutableStateFlow(false) }

                suspend fun observe() {
                    isCancelled.collect { cancelled -> if (!cancelled) download() }
                }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for Worker.observe: {(1200, 1)}")
    }

    @Test
    fun `state flow contracts reject hidden eager network work in a lazy state initializer`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.collect

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            fun initialize() = Unit

            class Worker {
                private val isCancelled by lazy {
                    initialize()
                    MutableStateFlow(false)
                }

                suspend fun observe() { isCancelled.collect { } }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Eager network work in a delegated lazy Flow initializer is unsupported")
    }

    @Test
    fun `state flow contracts keep an eagerly started producer separate from empty state observation`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.StateFlow
            import kotlinx.coroutines.flow.asStateFlow
            import kotlinx.coroutines.flow.collect
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 1_300, completeTimeoutMillis = 1_000)
            suspend fun download() = 1

            fun start(scope: CoroutineScope): StateFlow<Int> {
                val state = MutableStateFlow(0)
                scope.launch { state.value = download() }
                return state.asStateFlow()
            }

            suspend fun observe(scope: CoroutineScope) { start(scope).collect { } }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for start: {(1300, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for observe: {(1300, 1)}")
    }

    @Test
    fun `state flow contracts include completing collectLatest actions alongside their producer`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.collectLatest
            import kotlinx.coroutines.flow.flow

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun nextValue() = 1

            @NetworkDownload(maxBytes = 1_500, completeTimeoutMillis = 1_000)
            suspend fun download(value: Int) = Unit

            suspend fun observe() {
                flow { emit(nextValue()) }.collectLatest { download(it) }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for observe: {(1500, 2)}")
    }

    @Test
    fun `state flow contracts reject unbounded work escaping collectLatest actions`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.collectLatest
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 600, completeTimeoutMillis = 1_000)
            suspend fun download(value: Int) = Unit

            suspend fun observe(scope: CoroutineScope) {
                listOf(1, 2).asFlow().collectLatest { value ->
                    scope.launch { download(value) }
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work in a Flow collector callback")
    }

    @Test
    fun `state flow contracts retain callbacks observing a local shared event bus property`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.channels.BufferOverflow
            import kotlinx.coroutines.flow.MutableSharedFlow
            import kotlinx.coroutines.flow.SharedFlow
            import kotlinx.coroutines.flow.asSharedFlow
            import kotlinx.coroutines.flow.collect

            @NetworkDownload(maxBytes = 1_600, completeTimeoutMillis = 1_000)
            suspend fun download(value: Int) = Unit

            class EventBus {
                private val mutableEvents = MutableSharedFlow<Int>(
                    replay = 0,
                    extraBufferCapacity = 4,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
                val events: SharedFlow<Int> = mutableEvents.asSharedFlow()
            }

            suspend fun observe(bus: EventBus) { bus.events.collect { download(it) } }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for observe: {(1600, 1)}")
    }

    @Test
    fun `state flow contracts charge eager shared flow factory arguments once`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.flow.MutableSharedFlow
            import kotlinx.coroutines.flow.asSharedFlow
            import kotlinx.coroutines.flow.collect

            @NetworkDownload(maxBytes = 700, completeTimeoutMillis = 1_000)
            fun capacity() = 4

            fun buildOnly() { MutableSharedFlow<Int>(extraBufferCapacity = capacity()).asSharedFlow() }

            suspend fun observe() {
                val events = MutableSharedFlow<Int>(extraBufferCapacity = capacity()).asSharedFlow()
                events.collect { it + 1 }
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for buildOnly: {(700, 1)}")
        result.assertOutputContains("Inferred bandwidth effect for observe: {(700, 1)}")
    }

    private val roomQueryFlowStub = """
        package androidx.room.coroutines
        import kotlinx.coroutines.flow.Flow
        fun <R> createFlow(
            db: Any,
            inTransaction: Boolean,
            tableNames: Array<String>,
            block: (Int) -> R,
        ): Flow<R> = error("audited library boundary")
    """

    private val ktorRoutingStub = """
        package io.ktor.server.routing

        class Application
        open class Route
        class Routing : Route()
        class RoutingContext

        fun Application.routing(configuration: Routing.() -> Unit) = Unit

        fun Route.route(path: String, build: Route.() -> Unit) = Unit

        fun Route.get(
            path: String,
            body: suspend RoutingContext.() -> Unit,
        ) = Unit

        fun Route.post(
            path: String,
            body: suspend RoutingContext.() -> Unit,
        ) = Unit
    """

    private val ktorWebSocketStub = """
        package io.ktor.server.websocket

        import io.ktor.server.routing.Route

        fun Route.webSocketRaw(path: String, handler: suspend () -> Unit) = Unit
    """

    private val ktorResourcesStub = """
        package io.ktor.server.resources

        import io.ktor.server.routing.Route
        import io.ktor.server.routing.RoutingContext

        inline fun <reified T : Any> Route.get(
            noinline body: suspend RoutingContext.(T) -> Unit,
        ) = Unit


        inline fun <reified T : Any> Route.post(
            noinline body: suspend RoutingContext.(T) -> Unit,
        ) = Unit
    """

    private val ktorSwaggerStub = """
        package de.nielsfalk.ktor.swagger

        import io.ktor.server.routing.Route
        import io.ktor.server.routing.RoutingContext

        class Metadata

        inline fun <reified T : Any> Route.get(
            metadata: Metadata,
            noinline body: suspend RoutingContext.(T) -> Unit,
        ) = Unit
    """

    @Test
    fun `bounded background semantics external async overlaps its continuation`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.async

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun background() = Unit

            @NetworkDownload(maxBytes = 2_000, completeTimeoutMillis = 1_000)
            suspend fun foreground() = Unit

            suspend fun load(scope: CoroutineScope) {
                scope.async { background() }
                foreground()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(2000, 2), (1000, 2)}")
        result.assertOutputContains("ReqBW=4000 bytes/s")
    }

    @Test
    fun `bounded background semantics retains two escaped bodies across sequential continuation`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.async
            import kotlinx.coroutines.launch

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun heavy() = Unit

            @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
            suspend fun light() = Unit

            suspend fun load(scope: CoroutineScope) {
                scope.launch { heavy(); light() }
                scope.async { light(); light() }
                light()
                light()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        // Three concurrent branches; sequential downloads inside each branch do not add copies.
        result.assertOutputContains("Inferred bandwidth effect for load: {(1000, 3)}")
        result.assertOutputContains("ReqBW=3000 bytes/s")
    }

    @Test
    fun `bounded background semantics repeated escaped builders hold their own shared permits`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.async
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private val slots = Semaphore(3)

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun background() = Unit

            @NetworkDownload(maxBytes = 2_000, completeTimeoutMillis = 1_000)
            suspend fun foreground() = Unit

            suspend fun repeatedLaunches(count: Int, scope: CoroutineScope) {
                repeat(count) { scope.launch { slots.withPermit { background() } } }
                foreground()
            }

            suspend fun repeatedAsyncs(count: Int, scope: CoroutineScope) {
                repeat(count) { scope.async { slots.withPermit { background() } } }
                foreground()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for repeatedLaunches: {(2000, 4), (1000, 4)}")
        result.assertOutputContains("Inferred bandwidth effect for repeatedAsyncs: {(2000, 4), (1000, 4)}")
        result.assertOutputContains("ReqBW=8000 bytes/s")
    }

    @Test
    fun `bounded background semantics rejects nested detached work after a permit is released`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.launch
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private val slots = Semaphore(3)

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            fun load(count: Int, scope: CoroutineScope) {
                repeat(count) {
                    scope.launch {
                        slots.withPermit { scope.launch { download() } }
                    }
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `bounded background semantics launchIn overlaps collection with later work`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.launchIn

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun background() = Unit

            @NetworkDownload(maxBytes = 2_000, completeTimeoutMillis = 1_000)
            suspend fun foreground() = Unit

            suspend fun load(scope: CoroutineScope) {
                flow { background(); emit(Unit) }.launchIn(scope)
                foreground()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(2000, 2), (1000, 2)}")
        result.assertOutputContains("ReqBW=4000 bytes/s")
    }

    @Test
    fun `bounded background semantics repeated launchIn requires a real producer bound`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.launchIn

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            fun load(count: Int, scope: CoroutineScope) {
                repeat(count) { flow { download(); emit(Unit) }.launchIn(scope) }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `bounded background semantics repeated launchIn preserves a producer owned permit`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.launchIn
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private val slots = Semaphore(3)

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun background() = Unit

            @NetworkDownload(maxBytes = 2_000, completeTimeoutMillis = 1_000)
            suspend fun foreground() = Unit

            suspend fun load(count: Int, scope: CoroutineScope) {
                repeat(count) {
                    flow { slots.withPermit { background() }; emit(Unit) }.launchIn(scope)
                }
                foreground()
            }
            """,
            reportEffects = true,
        )

        assertEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Inferred bandwidth effect for load: {(2000, 4), (1000, 4)}")
        result.assertOutputContains("ReqBW=8000 bytes/s")
    }

    @Test
    fun `bounded background semantics permit around launchIn does not bound its collection`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.NetworkDownload
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.flow
            import kotlinx.coroutines.flow.launchIn
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit

            private val slots = Semaphore(3)

            @NetworkDownload(maxBytes = 1_000, completeTimeoutMillis = 1_000)
            suspend fun download() = Unit

            suspend fun load(count: Int, scope: CoroutineScope) {
                repeat(count) {
                    slots.withPermit { flow { download(); emit(Unit) }.launchIn(scope) }
                }
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Escaping network work")
        result.assertOutputContains("Use a self bound for every download")
    }

    @Test
    fun `bounded background semantics launchIn rejects an unknown Flow producer`() {
        val result = compile(
            """
            import kotlinx.coroutines.CoroutineScope
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.launchIn

            interface Source {
                fun stream(): Flow<Unit>
            }

            fun load(source: Source, scope: CoroutineScope) {
                source.stream().launchIn(scope)
            }
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains("Cannot infer launchIn on an untracked Flow")
    }

    private fun phasedHandlerFixture(permits: Int = 3): String = """
        import io.github.loinguyen.bandwidth.annotations.NetworkDownload
        import kotlinx.coroutines.coroutineScope
        import kotlinx.coroutines.launch
        import kotlinx.coroutines.sync.Semaphore
        import kotlinx.coroutines.sync.withPermit

        private val handlerSlots = Semaphore($permits)

        @NetworkDownload(maxBytes = 100, completeTimeoutMillis = 1_000)
        suspend fun high() = Unit

        @NetworkDownload(maxBytes = 1, completeTimeoutMillis = 1_000)
        suspend fun low() = Unit

        suspend fun gatedRequest() = handlerSlots.withPermit {
            high()
            coroutineScope {
                ${List(10) { "launch { low() }" }.joinToString("\n")}
            }
        }
    """

    private fun compile(
        source: String,
        reportEffects: Boolean = false,
    ): CompilationResult = compileSources(
        sources = listOf(source),
        reportEffects = reportEffects,
    )

    private fun compileSources(
        sources: List<String>,
        reportEffects: Boolean = false,
    ): CompilationResult {
        val directory: Path = Files.createTempDirectory("bandwidth-compiler-test")
        val sourceFiles: List<Path> = sources.mapIndexed { index, source ->
            val fileName = if (index == 0) "Test.kt" else "Test${index + 1}.kt"
            directory.resolve(fileName).also {
                it.writeText(source.trimIndent())
            }
        }
        val outputDirectory: Path = directory.resolve("classes")
        Files.createDirectories(outputDirectory)

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
        command += sourceFiles.map(Path::toString)

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
        fun assertFunctionBandwidth(function: String, bytesPerSecond: Int) {
            val line = Regex(
                "Inferred bandwidth effect for ${Regex.escape(function)}: .*?" +
                    "ReqBW=$bytesPerSecond bytes/s",
                RegexOption.IGNORE_CASE,
            )
            assertEquals(true, line.containsMatchIn(output), output)
        }

        fun assertOutputContains(expected: String) {
            assertContains(output.lowercase(), expected.lowercase())
        }
    }
}
