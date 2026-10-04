plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

dependencies {
    implementation(libs.apache.commons.io)
    implementation(libs.zt.zip)
    implementation(libs.apache.commons.text)
    implementation(libs.apache.commons.io)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val versionGenDir: Provider<Directory> = project.layout.buildDirectory.dir("version_gen")
val generateVersionVariable by tasks.registering {
    doLast {
        val outputDir = versionGenDir.map { it.dir("org/modelix/buildtools") }.get().asFile
        outputDir.mkdirs()
        outputDir.resolve("Version.kt").writeText(
            """
            package org.modelix.buildtools

            const val modelixBuildToolsVersion: String = "$version"

            """.trimIndent(),
        )
    }
}
kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(versionGenDir)
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().all {
    dependsOn(generateVersionVariable)
}

/**
 * Compiles and runs the tests with a different Java version than the main sources.
 */
fun Project.useJavaVersionForTests(javaVersion: Int) {
    val javaLanguageVersion = JavaLanguageVersion.of(javaVersion)
    configurations.named("testCompileClasspath") {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, javaVersion)
    }
    configurations.named("testRuntimeClasspath") {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, javaVersion)
    }
    tasks.named<JavaCompile>("compileTestJava") {
        javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(javaLanguageVersion) })
        options.release.set(javaVersion)
    }
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>("compileTestKotlin") {
        kotlinJavaToolchain.toolchain.use(javaToolchains.launcherFor { languageVersion.set(javaLanguageVersion) })
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(javaVersion.toString()))
    }
    tasks.named<Test>("test") {
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(javaLanguageVersion) })
    }
}

// The library itself targets Java 8, but JUnit 6 requires Java 17.
useJavaVersionForTests(17)

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("buildTools") {
            groupId = project.group.toString()
            artifactId = "build-tools-lib"
            version = project.version.toString()

            from(components["java"])
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
    withSourcesJar()
}
