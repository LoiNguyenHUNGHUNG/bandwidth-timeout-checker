plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.buildconfig)
}

val kotlinCompilerVersion =
    providers.gradleProperty("kotlinVersion").orElse(libs.versions.kotlin)
val kotlinAdapterDirectory = when {
    kotlinCompilerVersion.get().startsWith("2.3.") -> "src-kotlin-2.3"
    kotlinCompilerVersion.get().startsWith("2.4.") -> "src-kotlin-2.4"
    else -> error(
        "Unsupported Kotlin compiler ${kotlinCompilerVersion.get()}. " +
            "Supported lines: 2.3.x, 2.4.x.",
    )
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src", kotlinAdapterDirectory))
        resources.setSrcDirs(listOf("resources"))
    }
    test {
        java.setSrcDirs(listOf("test"))
    }
}

val embedded: Configuration = configurations.create("embedded") {
    isTransitive = false
}

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)
    implementation(project(":checker-core"))
    embedded(project(":checker-core"))

    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.dagger)
    testImplementation(project(":plugin-annotations"))
}

tasks.jar {
    from(embedded.map { dependency -> zipTree(dependency) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

buildConfig {
    useKotlinOutput {
        internalVisibility = true
    }

    packageName(group.toString())
    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.group}\"")
}

kotlin {
    compilerOptions {
        if (kotlinCompilerVersion.get().startsWith("2.3.")) {
            freeCompilerArgs.add("-Xcontext-parameters")
        }
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.fir.symbols.SymbolInternals")
    }
}

tasks.test {
    dependsOn(tasks.jar)
    useJUnitPlatform()
    systemProperty("bandwidth.compiler.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    systemProperty(
        "bandwidth.compiler.plugin.jar",
        tasks.jar.get().archiveFile.get().asFile.absolutePath,
    )
}
