import org.modelix.gradle.mpsbuild.MPSBuildSettings

plugins {
    base
    `maven-publish`
    id("org.modelix.mpsbuild")
}

group = "org.modelix"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
}

extensions.configure<MPSBuildSettings> {
    mpsVersion("2021.1.4")
    search("../generator-test-project")

    publication("L1") {
        module("test.org.modelix.generatortestproject.L1")
    }
    publication("L2") {
        module("test.org.modelix.generatortestproject.L2")
    }
}
