package io.github.loinguyen.bandwidth.compiler

import io.github.loinguyen.bandwidth.core.AnalysisMode
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal object BandwidthConfiguration {
    val ANALYSIS_MODE: CompilerConfigurationKey<AnalysisMode> =
        CompilerConfigurationKey.create("bandwidth effect analysis mode")
    val ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("enable bandwidth-timeout checking")
    val REPORT_EFFECTS: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("report inferred bandwidth effects")
}
