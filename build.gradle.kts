plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.buildconfig) apply false
}

val kotlinVersionOverride = providers.gradleProperty("kotlinVersion").orNull

allprojects {
    group = "io.github.loinguyen.bandwidth"
    version = "0.1.0-SNAPSHOT"

    if (kotlinVersionOverride != null) {
        configurations.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion(kotlinVersionOverride)
                    because("the compiler plugin must use the target Kotlin compiler version")
                }
            }
        }
    }
}
