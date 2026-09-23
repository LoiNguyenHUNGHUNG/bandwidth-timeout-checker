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
    fun `rejects a symbolic annotation on the whole function`() {
        val result = compile(
            """
            import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
            import io.github.loinguyen.bandwidth.annotations.BandwidthVariable

            @BandwidthVariable("E")
            @BandwidthEffect("E")
            fun invoke(@BandwidthEffect("E") callback: () -> Unit) = callback()
            """,
        )

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Symbolic @BandwidthEffect variables belong only on higher-order inputs",
        )
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
    fun `rejects Flow collector callbacks across compiler versions`() {
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

        assertNotEquals(0, result.exitCode, result.output)
        result.assertOutputContains(
            "Cannot infer collect with a collector callback consistently across supported " +
                "Kotlin versions",
        )
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

    private val ktorRoutingStub = """
        package io.ktor.server.routing

        class Application
        open class Route
        class Routing : Route()
        class RoutingContext

        fun Application.routing(configuration: Routing.() -> Unit) = Unit

        fun Route.get(
            path: String,
            body: suspend RoutingContext.() -> Unit,
        ) = Unit

        fun Route.post(
            path: String,
            body: suspend RoutingContext.() -> Unit,
        ) = Unit
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
        fun assertOutputContains(expected: String) {
            assertContains(output.lowercase(), expected.lowercase())
        }
    }
}
