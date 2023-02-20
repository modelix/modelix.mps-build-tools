buildscript {
    repositories {
        /* It is useful to have the central maven repo before the Itemis's one
           as it is more reliable */
        maven { url = uri("https://repo.maven.apache.org/maven2") }
        maven { url = uri("https://plugins.gradle.org/m2/") }
        mavenCentral()
        maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
        maven { url = uri("https://projects.itemis.de/nexus/content/repositories/mbeddr") }
    }

    dependencies {
    }
}

plugins {
    kotlin("jvm") version "1.5.31" apply false
    kotlin("plugin.serialization") version "1.5.31" apply false
    `maven-publish`
    id("com.palantir.git-version") version "0.13.0"
}

val githubCredentials = if (project.hasProperty("gpr.user") && project.hasProperty("gpr.key")) {
        project.findProperty("gpr.user").toString() to project.findProperty("gpr.key").toString()
    } else if (System.getenv("GITHUB_ACTOR") != null && System.getenv("GITHUB_TOKEN") != null) {
        System.getenv("GITHUB_ACTOR") to System.getenv("GITHUB_TOKEN")
    } else {
        logger.error("Please specify your github username (gpr.user) and access token (gpr.key) in ~/.gradle/gradle.properties")
        null
    }

group = "org.modelix.mpsbuild"
description = "Replacement for the MPS build language"

val versionFile = projectDir.resolve("version.txt")
version = if (versionFile.exists()) {
    versionFile.readText().trim()
} else {
    val gitVersion: groovy.lang.Closure<String> by extra
    gitVersion().also { versionFile.writeText(it) }
}
if (!project.findProperty("ciBuild")?.toString().toBoolean()) {
    version = "$version-SNAPSHOT"
}
println("Version: $version")

subprojects {
    apply(plugin = "maven-publish")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenLocal()
        maven { url = uri("https://repo.maven.apache.org/maven2") }
        mavenCentral()
        maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
        maven { url = uri("https://projects.itemis.de/nexus/content/repositories/mbeddr") }
    }

    publishing {
        repositories {
            if (githubCredentials != null) {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/modelix/mpsbuild")
                    if (githubCredentials != null) {
                        credentials {
                            username = githubCredentials.first
                            password = githubCredentials.second
                        }
                    }
                }
            }
            if (project.hasProperty("artifacts.itemis.cloud.user")) {
                maven {
                    name = "itemisNexus3"
                    url = if (version.toString().contains("SNAPSHOT"))
                        uri("https://artifacts.itemis.cloud/repository/maven-mps-snapshots/")
                    else
                        uri("https://artifacts.itemis.cloud/repository/maven-mps-releases/")
                    credentials {
                        username = project.findProperty("artifacts.itemis.cloud.user").toString()
                        password = project.findProperty("artifacts.itemis.cloud.pw").toString()
                    }
                }
            }
            if (project.hasProperty("projects.itemis.de.user")) {
                maven {
                    name = "itemisNexus2"
                    url = if (version.toString().contains("SNAPSHOT"))
                        uri("https://projects.itemis.de/nexus/content/repositories/mbeddr_snapshots/")
                    else
                        uri("https://projects.itemis.de/nexus/content/repositories/mbeddr/")
                    credentials {
                        username = project.findProperty("projects.itemis.de.user").toString()
                        password = project.findProperty("projects.itemis.de.pw").toString()
                    }
                }
            }
        }
    }
}

defaultTasks("assemble")
