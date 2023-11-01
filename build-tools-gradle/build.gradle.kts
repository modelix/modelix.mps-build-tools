plugins {
    `maven-publish`
    `kotlin-dsl`
    kotlin("jvm")
}

repositories {
    mavenCentral()
    maven { url = uri("https://projects.itemis.de/nexus/content/repositories/mbeddr") }
}

dependencies {
    implementation(project(":build-tools-lib"))
    implementation(project(":build-tools-invoke-lambda"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.zeroturnaround:zt-zip:1.14")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        register("mpsbuildPlugin") {
            id = "org.modelix.mps.build-tools"
            implementationClass = "org.modelix.gradle.mpsbuild.MPSBuildPlugin"
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
    withSourcesJar()
}
