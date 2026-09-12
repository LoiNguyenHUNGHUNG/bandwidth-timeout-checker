package io.github.loinguyen.bandwidth.compiler

import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal object BandwidthConfiguration {
    val ENABLED: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("enable bandwidth-timeout checking")
    val REPORT_EFFECTS: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("report inferred bandwidth effects")
    val ENTRY_POINTS: CompilerConfigurationKey<Set<String>> =
        CompilerConfigurationKey.create("bandwidth analysis entry points")
}
