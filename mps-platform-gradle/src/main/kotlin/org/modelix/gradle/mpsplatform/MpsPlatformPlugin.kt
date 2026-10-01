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

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformDependenciesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

/** The Maven repository containing the MPS distributions (com.jetbrains:mps). */
const val MPS_REPOSITORY_URL = "https://artifacts.itemis.cloud/repository/maven-mps/"

/**
 * Compiles against and runs the tests with the MPS selected by `mps.version.major`/`mps.version` (see [mpsVersion]).
 * Projects that build an actual MPS plugin apply `org.modelix.mps.plugin` instead.
 *
 * MPS is used as a local platform of the IntelliJ Platform Gradle Plugin, which is applied by this plugin.
 */
class MpsPlatformPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("org.jetbrains.intellij.platform")

            repositories.intellijPlatform {
                localPlatformArtifacts()
            }
            // MPS is extracted while the plugin is applied, which is before the repositories of the build script
            // are configured. Repositories of the settings are ignored, because the project now declares its own.
            repositories.exclusiveContent {
                forRepository {
                    repositories.maven(MPS_REPOSITORY_URL)
                }
                filter {
                    includeModule("com.jetbrains", "mps")
                }
            }

            val mpsHome = copyMps()
            (dependencies as ExtensionAware).extensions.getByType<IntelliJPlatformDependenciesExtension>().apply {
                local(mpsHome)
                testFramework(TestFrameworkType.Bundled)
            }

            extensions.configure<IntelliJPlatformExtension> {
                instrumentCode.set(false)
                buildSearchableOptions.set(false)
                pluginVerification {
                    ides {
                        // Without any IDEs configured, the recommended ones would be downloaded (e.g. by the IDE sync).
                        current()
                    }
                }
            }

            plugins.withType<JavaPlugin> {
                configureMpsTestClasspath()
                tasks.named<Test>(JavaPlugin.TEST_TASK_NAME) {
                    configureMpsTestTask()
                }
            }
        }
    }
}
