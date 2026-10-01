/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modelix.gradle.mpsplatform

import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.getByType

/**
 * Excludes the libraries that are bundled with MPS. Use it for dependencies that end up in an MPS plugin or on the
 * classpath of tests running MPS, so the versions bundled with MPS are used instead.
 */
val excludeMPSLibraries: (ModuleDependency).() -> Unit = {
    exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-core")
    exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-jdk8")
    exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-swing")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-common")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk7")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
    exclude("org.jetbrains", "annotations")
}

/**
 * Project-level adjustments for projects whose tests boot MPS via the IntelliJ Platform Gradle Plugin.
 * Applied by the `org.modelix.mps.platform` plugin. Configure the test tasks themselves with [configureMpsTestTask].
 */
fun Project.configureMpsTestClasspath() {
    configurations.named("testRuntimeClasspath").configure {
        // MPS bundles JNA (in lib/util-8.jar) together with its native library, which the test JVM is pointed at
        // (see configureMpsTestTask). A different JNA version on the test classpath (e.g. from testcontainers) would
        // be loaded first and fail with "There is an incompatible JNA native library installed on this system".
        exclude(group = "net.java.dev.jna", module = "jna")

        // MPS 2025.1+ bundles JetBrains' coroutines fork (lib/util-8.jar) whose BuildersKt has
        // runBlockingWithParallelismCompensation, which the platform calls during boot. The vanilla
        // kotlinx-coroutines-core pulled in transitively lacks that method, so keep it off the test
        // runtime classpath and let MPS's bundled coroutines win.
        if (mpsPlatformVersion >= 251) {
            exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
            exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
        }
    }
}

/**
 * Configures a single test task that boots MPS via the IntelliJ Platform Gradle Plugin. Pair it with
 * [configureMpsTestClasspath] on the owning project. The `org.modelix.mps.platform` plugin applies it to the `test`
 * task.
 */
fun Test.configureMpsTestTask() {
    // The tests run MPS itself, which may require a newer Java version (MPS 2025.1+ needs Java 21) than the one the
    // code is compiled for.
    javaLauncher.set(
        project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(project.mpsJavaVersion))
        },
    )

    // MPS 2025.1+ loads platform services (e.g. SettingsController) from module descriptors in
    // lib/modules/*.jar, which the IntelliJ Platform Gradle Plugin doesn't put on the test classpath.
    // They are appended to the classpath instead of being added as dependencies, because dependencies
    // end up in the test sandbox of the plugin, where they could shadow newer versions of libraries
    // (e.g. Ktor) that the plugin brings itself. Older MPS has no lib/modules.
    classpath += project.fileTree(project.mpsHomeDir) { include("lib/modules/*.jar") }

    // Use a provider to avoid eagerly resolving the MPS home dir. It's obtained outside the provider, because the
    // project must not be accessed at execution time.
    val mpsHomeDir = project.mpsHomeDir
    jvmArgumentProviders.add {
        buildList {
            // JNA's native libraries live under lib/jna/<arch> in the MPS home. Point the test JVM there
            // so JNA loads the bundled library instead of trying to unpack one from the classpath.
            val jnaDir = mpsHomeDir.get().asFile.resolve("lib/jna/${System.getProperty("os.arch")}")
            if (jnaDir.exists()) {
                add("-Djna.boot.library.path=${jnaDir.absolutePath}")
                add("-Djna.noclasspath=true")
                add("-Djna.nosys=true")
            }

            add("-Dintellij.platform.load.app.info.from.resources=true")
        }
    }
}
