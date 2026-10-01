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
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

/**
 * Builds an MPS plugin that is compatible with all [SUPPORTED_MPS_VERSIONS]. Applies `org.modelix.mps.platform`.
 *
 * Use [publishMpsPlugin] to publish the plugin zip.
 * If an MPS installation is found (see [mpsPluginsDir]), the task `installMpsPlugin` copies the plugin into it.
 */
class MpsPluginPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply(MpsPlatformPlugin::class.java)

            extensions.configure<IntelliJPlatformExtension> {
                autoReload.set(true)
                pluginConfiguration {
                    ideaVersion {
                        sinceBuild.set(MPS_PLUGIN_SINCE_BUILD)
                        untilBuild.set(MPS_PLUGIN_UNTIL_BUILD)
                    }
                }
            }

            val pluginsDir = mpsPluginsDir
            if (pluginsDir != null) {
                tasks.register<Sync>("installMpsPlugin") {
                    group = "intellij platform"
                    description = "Copies the plugin into the plugins folder of MPS ($pluginsDir)."
                    from(tasks.named<PrepareSandboxTask>("prepareSandbox").flatMap { it.pluginDirectory })
                    into(pluginsDir.resolve(project.name))
                }
            }
        }
    }
}
