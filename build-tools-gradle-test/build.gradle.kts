buildscript {
    repositories {
        maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
    }
}

plugins {
    base
    `maven-publish`
    id("org.modelix.mps.build-tools")
    kotlin("jvm") version "2.2.20"
}

group = "org.modelix"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
}

val mpsVersion = project.findProperty("mps.version")?.toString().takeIf { !it.isNullOrBlank() } ?: "2025.2.1"
dependencies {
    compileOnly("com.jetbrains:mps-core:$mpsVersion")
    compileOnly("com.jetbrains:mps-openapi:$mpsVersion")
//    compileOnly("com.jetbrains:mps-environment:$mpsVersion")
//    compileOnly("com.jetbrains:mps-platform:$mpsVersion")
}

mpsBuild {
    mpsVersion(mpsVersion)
    search("../generator-test-project")

    publication("L1") {
        module("test.org.modelix.generatortestproject.L1")
    }
    publication("L2") {
        module("test.org.modelix.generatortestproject.L2")
    }

    runMPS("listModules") {
        mainMethodFqName("org.modelix.buildtools.test.ListModulesKt.listModules")
        includeProjectRuntime()
    }
}

tasks.named("test") {
    dependsOn("listModules")
}
