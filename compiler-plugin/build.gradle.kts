plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.buildconfig)
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
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
    compileOnly(libs.kotlin.compiler)
    implementation(project(":checker-core"))
    embedded(project(":checker-core"))

    testImplementation(libs.kotlin.compiler)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.core)
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
