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
