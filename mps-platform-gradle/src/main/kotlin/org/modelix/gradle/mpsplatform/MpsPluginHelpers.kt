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
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.jetbrains.intellij.platform.gradle.tasks.PatchPluginXmlTask
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

/**
 * Copies the META-INF folder of the resources, with the patched plugin.xml, into the plugin folder of the sandbox.
 */
fun PrepareSandboxTask.includeMetaInfFolder() {
    from(project.layout.projectDirectory.dir("src/main/resources/META-INF")) {
        exclude("plugin.xml")
        into(pluginName.map { "$it/META-INF" })
    }
    from(project.tasks.named("patchPluginXml", PatchPluginXmlTask::class.java).flatMap { it.outputFile }) {
        into(pluginName.map { "$it/META-INF" })
    }
}

/**
 * Publishes the zip of the MPS plugin built by `org.modelix.mps.plugin` (publication `mpsPlugin`).
 */
fun Project.publishMpsPlugin(artifactId: String = name) {
    pluginManager.apply("maven-publish")
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("mpsPlugin") {
            this.artifactId = artifactId
            artifact(tasks.named("buildPlugin")) {
                extension = "zip"
            }
        }
    }
}
