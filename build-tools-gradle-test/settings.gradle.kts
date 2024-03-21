pluginManagement {
    val mpsBuildVersion: String = file("../version.txt").readText()
    plugins {
        id("org.modelix.mps.build-tools") version mpsBuildVersion
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
rootProject.name = "build-tools-gradle-test"
