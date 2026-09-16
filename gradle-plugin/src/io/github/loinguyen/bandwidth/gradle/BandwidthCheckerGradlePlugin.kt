package io.github.loinguyen.bandwidth.gradle

import io.github.loinguyen.bandwidth.BuildConfig
import io.github.loinguyen.bandwidth.BuildConfig.ANNOTATIONS_LIBRARY_COORDINATES
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

@Suppress("unused") // Loaded by Gradle from the plugin marker.
class BandwidthCheckerGradlePlugin : KotlinCompilerPluginSupportPlugin {
    /** Registers the `bandwidthChecker` extension on [target]. */
    override fun apply(target: Project) {
        target.extensions.create(
            "bandwidthChecker",
            BandwidthCheckerGradleExtension::class.java,
        )
    }

    /** Returns `true` for JVM compilations, the platform supported by the checker. */
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.target.platformType == KotlinPlatformType.jvm ||
            kotlinCompilation.target.platformType == KotlinPlatformType.androidJvm

    /** Returns the compiler plugin identifier passed to Kotlin Gradle tooling. */
    override fun getCompilerPluginId(): String = BuildConfig.KOTLIN_PLUGIN_ID

    /** Returns the coordinates of the compiler-plugin artifact Gradle must load. */
    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = BuildConfig.KOTLIN_PLUGIN_GROUP,
        artifactId = BuildConfig.KOTLIN_PLUGIN_NAME,
        version = BuildConfig.KOTLIN_PLUGIN_VERSION,
    )

    /**
     * Adds the annotations dependency, orders the compiler plugin after
     * Compose, and maps extension values to compiler options.
     *
     * @param kotlinCompilation the compilation being configured.
     * @return a provider of options evaluated from the project extension.
     */
    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>,
    ): Provider<List<SubpluginOption>> {
        val project: Project = kotlinCompilation.target.project

        kotlinCompilation.defaultSourceSet.dependencies {
            implementation(ANNOTATIONS_LIBRARY_COORDINATES)
        }

        kotlinCompilation.compileTaskProvider.configure {
            it.compilerOptions.freeCompilerArgs.add(
                "-Xcompiler-plugin-order=${BuildConfig.KOTLIN_PLUGIN_ID}>androidx.compose.compiler.plugins.kotlin",
            )
        }

        return project.provider {
            val extension: BandwidthCheckerGradleExtension =
                project.extensions.getByType(BandwidthCheckerGradleExtension::class.java)
            listOf(
                SubpluginOption("enabled", extension.enabled.get().toString()),
                SubpluginOption("reportEffects", extension.reportEffects.get().toString()),
            )
        }
    }
}
