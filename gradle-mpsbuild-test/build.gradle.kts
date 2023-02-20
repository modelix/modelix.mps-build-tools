plugins {
    kotlin("jvm") version "1.8.0"
    id("org.modelix.mpsbuild")
}

group = "org.modelix"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
