package io.github.loinguyen.bandwidth.compiler

import io.github.loinguyen.bandwidth.BuildConfig

/** Kotlin 2.3 compiler-plugin entry point loaded through `ServiceLoader`. */
@Suppress("unused")
class BandwidthCompilerPluginRegistrar : AbstractBandwidthCompilerPluginRegistrar() {
    override val pluginId: String = BuildConfig.KOTLIN_PLUGIN_ID
}
