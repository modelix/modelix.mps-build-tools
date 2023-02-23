pluginManagement {
    val mpsBuildVersion: String = file("../version.txt").readText()
    plugins {
        id("org.modelix.mpsbuild") version mpsBuildVersion
    }
    repositories {
        repositories {
            mavenLocal()
            gradlePluginPortal()
            maven { url = uri("https://artifacts.itemis.cloud/repository/maven-mps/") }
            mavenCentral()
        }
    }
}
rootProject.name = "gradle-mpsbuild-test"

