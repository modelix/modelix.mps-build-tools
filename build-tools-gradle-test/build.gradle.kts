import jetbrains.mps.smodel.MPSModuleRepository

buildscript {
    repositories {
        maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
    }
    dependencies {
        classpath("com.jetbrains:mps-openapi:2021.1.4")
        classpath("com.jetbrains:mps-core:2021.1.4")
        classpath("com.jetbrains:mps-environment:2021.1.4")
        classpath("com.jetbrains:mps-platform:2021.1.4")
    }
}

plugins {
    base
    `maven-publish`
    id("org.modelix.mps.build-tools")
    kotlin("jvm") version "1.9.23"
}

group = "org.modelix"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
}

val mpsVersion = project.findProperty("mps.version")?.toString().takeIf { !it.isNullOrBlank() } ?: "2021.1.4"
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

    runMPS("runLambdaInMPS") {
        val prefix = "jetbrains."
        implementation {
            val repo = MPSModuleRepository.getInstance()
            lateinit var moduleNames: List<String?>
            repo.modelAccess.runReadAction {
                moduleNames = repo.modules.filter { it.moduleName?.startsWith(prefix) == true }.map { it.moduleName }
            }
            moduleNames.forEach { println("Module: $it") }
        }
    }

    runMPS("runLambdaInMPSWithResult") {
        implementation({
            val repo = MPSModuleRepository.getInstance()
            lateinit var moduleNames: List<String?>
            repo.modelAccess.runReadAction {
                moduleNames = repo.modules.map { it.moduleName }.toList()
            }
            moduleNames.size
        }, {
            println("Number of modules: $it")
        })
    }
}

tasks.named("test") {
    dependsOn("listModules", "runLambdaInMPS", "runLambdaInMPSWithResult")
}
