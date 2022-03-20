import java.text.SimpleDateFormat
import java.util.*

buildscript {
    repositories {
        /* It is useful to have the central maven repo before the Itemis's one
           as it is more reliable */
        mavenLocal()
        maven { url = uri("https://repo.maven.apache.org/maven2") }
        maven { url = uri("https://plugins.gradle.org/m2/") }
        mavenCentral()
        maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
        maven { url = uri("https://projects.itemis.de/nexus/content/repositories/mbeddr") }
    }

    dependencies {
        classpath("de.itemis.mps:mps-gradle-plugin:mps20211.1.5.281.69e6edc")
        classpath("com.google.googlejavaformat:google-java-format:1.8+")
    }
}

plugins {
    id("com.diffplug.gradle.spotless") version "4.5.1" apply false
    id("org.jetbrains.kotlin.jvm") version "1.6.10" apply false
    id("org.jetbrains.kotlin.multiplatform") version "1.6.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.6.10" apply false
    id("maven-publish")
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

val gitVersion: groovy.lang.Closure<String> by extra
gitVersion()
version = gitVersion()
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
