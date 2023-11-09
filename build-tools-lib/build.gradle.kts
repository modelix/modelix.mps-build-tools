plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

dependencies {
    implementation("org.zeroturnaround:zt-zip:1.14")
    implementation("org.apache.commons:commons-text:1.10.0")
    implementation("commons-io:commons-io:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.0")
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
