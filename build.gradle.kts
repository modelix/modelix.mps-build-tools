buildscript {
    repositories {
        maven { url = uri("https://repo.maven.apache.org/maven2") }
        maven { url = uri("https://plugins.gradle.org/m2/") }
        mavenCentral()
        maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
    }

    dependencies {
    }
}

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    `maven-publish`
    alias(libs.plugins.gitVersion)
}

val githubCredentials = if (project.hasProperty("gpr.user") && project.hasProperty("gpr.key")) {
    project.findProperty("gpr.user").toString() to project.findProperty("gpr.key").toString()
} else if (System.getenv("GITHUB_ACTOR") != null && System.getenv("GITHUB_TOKEN") != null) {
    System.getenv("GITHUB_ACTOR") to System.getenv("GITHUB_TOKEN")
} else {
    logger.error("Please specify your github username (gpr.user) and access token (gpr.key) in ~/.gradle/gradle.properties")
    null
}

group = "org.modelix.mps"
description = "Replacement for the MPS build language"

val versionFile = projectDir.resolve("version.txt")
version = if (versionFile.exists()) {
    versionFile.readText().trim()
} else {
    val gitVersion: groovy.lang.Closure<String> by extra
    gitVersion().let {
        if (!project.findProperty("ciBuild")?.toString().toBoolean()) {
            "$it-SNAPSHOT"
        } else {
            it
        }
    }.also { versionFile.writeText(it) }
}

println("Version: $version")

subprojects {
    apply(plugin = "maven-publish")

    group = rootProject.group
    version = rootProject.version

    repositories {
        maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
        maven { url = uri("https://repo.maven.apache.org/maven2") }
        mavenCentral()
        mavenLocal()
    }

    publishing {
        repositories {
            if (githubCredentials != null) {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/modelix/modelix.mps-build-tools")
                    credentials {
                        username = githubCredentials.first
                        password = githubCredentials.second
                    }
                }
            }
            if (project.hasProperty("artifacts.itemis.cloud.user")) {
                maven {
                    name = "itemisNexus3"
                    url = if (version.toString().contains("SNAPSHOT")) {
                        uri("https://artifacts.itemis.cloud/repository/maven-mps-snapshots/")
                    } else {
                        uri("https://artifacts.itemis.cloud/repository/maven-mps-releases/")
                    }
                    credentials {
                        username = project.findProperty("artifacts.itemis.cloud.user").toString()
                        password = project.findProperty("artifacts.itemis.cloud.pw").toString()
                    }
                }
            }
        }
    }

    // Set maven metadata for all known publishing tasks. The exact tasks and names are only known after evaluation.
    afterEvaluate {
        tasks.withType<AbstractPublishToMaven>() {
            this.publication?.apply {
                setMetadata()
            }
        }
    }
}

fun MavenPublication.setMetadata() {
    pom {
        url.set("https://github.com/modelix/modelix.mps-build-tools")
        scm {
            connection.set("scm:git:https://github.com/modelix/modelix.mps-build-tools.git")
            url.set("https://github.com/modelix/modelix.mps-build-tools")
        }
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
    }
}

defaultTasks("assemble")
