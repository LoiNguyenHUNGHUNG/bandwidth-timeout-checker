package io.github.loinguyen.bandwidth.gradle

import io.github.loinguyen.bandwidth.BuildConfig
import io.github.loinguyen.bandwidth.BuildConfig.ANNOTATIONS_LIBRARY_COORDINATES
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

@Suppress("unused") // Loaded by Gradle from the plugin marker.
class BandwidthCheckerGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        target.extensions.create(
            "bandwidthChecker",
            BandwidthCheckerGradleExtension::class.java,
        )
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun getCompilerPluginId(): String = BuildConfig.KOTLIN_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = BuildConfig.KOTLIN_PLUGIN_GROUP,
        artifactId = BuildConfig.KOTLIN_PLUGIN_NAME,
        version = BuildConfig.KOTLIN_PLUGIN_VERSION,
    )

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
