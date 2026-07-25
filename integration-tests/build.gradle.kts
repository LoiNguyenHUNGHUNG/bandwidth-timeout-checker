import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(project(":plugin-annotations"))
}

val compilerPluginJar = project(":compiler-plugin").tasks.named<Jar>("jar")

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(compilerPluginJar)
    compilerOptions.freeCompilerArgs.add(
        compilerPluginJar.flatMap { it.archiveFile }.map {
            "-Xplugin=${it.asFile.absolutePath}"
        },
    )
}
