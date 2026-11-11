package io.github.loinguyen.bandwidth.compiler

import io.github.loinguyen.bandwidth.core.AnalysisMode
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.config.CompilerConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalysisModeConfigurationTest {
    // The constructor is opt-in-only in Kotlin 2.4; the marker is absent in 2.2.
    @Suppress("OPT_IN_USAGE_ERROR")
    @Test
    fun `compiler configurations do not share analysis mode in one process`() {
        val processor = BandwidthCommandLineProcessor()
        val option: AbstractCliOption = processor.pluginOptions.single { it.optionName == "analysisMode" }
        val simplified = CompilerConfiguration()
        val diagnostic = CompilerConfiguration()
        val default = CompilerConfiguration()
        processor.processOption(option, "simplified", simplified)
        processor.processOption(option, "diagnostic", diagnostic)
        assertEquals(AnalysisMode.SIMPLIFIED, simplified.get(BandwidthConfiguration.ANALYSIS_MODE))
        assertEquals(AnalysisMode.DIAGNOSTIC, diagnostic.get(BandwidthConfiguration.ANALYSIS_MODE))
        assertNull(default.get(BandwidthConfiguration.ANALYSIS_MODE))
        assertEquals(AnalysisMode.DETAILED, default.get(BandwidthConfiguration.ANALYSIS_MODE, AnalysisMode.DETAILED))
    }
}
