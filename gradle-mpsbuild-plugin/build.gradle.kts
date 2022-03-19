plugins {
    id("java")
    id("maven-publish")
    id("org.jetbrains.kotlin.jvm")
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "11"
    targetCompatibility = "11"
}

repositories {
    mavenCentral()
    maven { url = uri("https://projects.itemis.de/nexus/content/repositories/mbeddr") }
}

dependencies {
    implementation(project(":mps-build-tools"))
    implementation("commons-io:commons-io:2.11.0")
    implementation("org.zeroturnaround:zt-zip:1.14")
    implementation(gradleApi())
    testImplementation("junit:junit:4.12")
}

publishing {
    publications {
        create("buildPlugin", MavenPublication::class.java) {
            groupId = project.group.toString()
            version = project.version.toString()
            from(components["java"])
        }
    }
}
