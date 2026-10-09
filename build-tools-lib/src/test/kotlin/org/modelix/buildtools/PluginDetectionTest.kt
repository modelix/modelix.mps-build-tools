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
package org.modelix.buildtools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginDetectionTest {

    private val pluginXml = """
        <idea-plugin>
          <id>jetbrains.mps.vcs</id>
          <name>MPS VCS Addons</name>
          <depends>com.intellij.modules.mps</depends>
        </idea-plugin>
    """.trimIndent()

    private val runtimeModuleXml = """
        <module namespace="jetbrains.mps.vcs.mergehints.runtime" type="solution" uuid="63089e65-5c76-4c44-9eb6-15698b4444cf">
          <dependencies />
          <library jar="../lib/vcs-core.jar" />
          <sources descriptor="jetbrains.mps.vcs.mergehints.runtime.msd" jar="jetbrains.mps.vcs.mergehints.runtime-src.jar" />
        </module>
    """.trimIndent()

    /**
     * The plugins bundled with MPS 2024.1 don't have a META-INF folder. The descriptor is only inside the jar.
     */
    @Test
    fun `plugin descriptor inside a jar of the lib folder`(@TempDir mpsHome: File) {
        createJar(mpsHome.resolve("lib/mps-boot.jar"), "readme.txt" to "")
        createJar(mpsHome.resolve("lib/mps-workbench.jar"), "META-INF/plugin.xml" to "<idea-plugin><id>com.intellij</id></idea-plugin>")
        val pluginFolder = mpsHome.resolve("plugins/mps-vcs")
        createJar(pluginFolder.resolve("lib/mps-vcs.jar"), "META-INF/plugin.xml" to pluginXml)
        createJar(pluginFolder.resolve("languages/jetbrains.mps.vcs.mergehints.runtime.jar"), "META-INF/module.xml" to runtimeModuleXml)

        assertTrue(PluginModuleOwner.isPluginFolder(pluginFolder))
        assertFalse(PluginModuleOwner.isPluginFolder(mpsHome))

        val miner = ModulesMiner()
        miner.searchInFolder(mpsHome)
        val modules = miner.getModules()

        val plugin = modules.plugins["jetbrains.mps.vcs"]
        assertEquals(pluginFolder.canonicalFile, plugin?.path?.getLocalAbsolutePath()?.toFile()?.canonicalFile)
        val runtime = modules.getModules().getValue(ModuleId("63089e65-5c76-4c44-9eb6-15698b4444cf"))
        assertEquals(plugin, runtime.owner.getRootOwner())
    }

    private fun createJar(file: File, vararg entries: Pair<String, String>) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
