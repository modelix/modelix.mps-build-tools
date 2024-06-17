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
}

val versionGenDir: Provider<Directory> = project.layout.buildDirectory.dir("version_gen")
val generateVersionVariable by tasks.creating {
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
