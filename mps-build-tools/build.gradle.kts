
description = "Replacement for the MPS build language"
val ktorVersion = "1.6.5"
val kotlinCoroutinesVersion = "1.5.2"
val kotlinVersion = "1.5.31"
val logbackVersion = "1.2.1"

plugins {
    kotlin("jvm")
    id("application")
    id("maven-publish")
}

dependencies {
    implementation("org.zeroturnaround:zt-zip:1.14")
    implementation("org.apache.commons:commons-text:1.9")
    implementation("commons-io:commons-io:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "org.modelix"
            artifactId = "mps-build-tools"
            version = project.version.toString()

            from(components["java"])
        }
    }
}
