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

import org.zeroturnaround.zip.ZipUtil
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import kotlin.io.path.readLines

/**
 * Modules packaged as an IDEA plugin containing a META-INF/plugin.xml file.
 */
class PluginModuleOwner(path: ModulePath, val pluginId: String, val name: String?, val pluginDependencies: Set<String>) : ModuleOwner(path) {
    val stacktrace = Thread.currentThread().stackTrace
    val libraries: MutableSet<LibraryModuleOwner> = HashSet()

    fun getModuleJarFolders(): List<File> {
        try {
            val pluginFolder = path.getLocalAbsolutePath().normalize().toFile()
            val pluginXml = readPluginXml(pluginFolder) ?: throw RuntimeException("No META-INF/plugin.xml found in $pluginFolder")
            val xml = readXmlFile(pluginXml.inputStream(), "$pluginFolder/META-INF/plugin.xml")
            val folders = xml.documentElement.childElements("extensions").flatMap { it.childElements() }
                .asSequence()
                .filter { it.tagName.endsWith("LanguageLibrary") }
                .map { it.getAttribute("dir") }
                .map { it.trimStart('/', '\\') }
                .map { pluginFolder.resolve(it).normalize() }
                .minus(pluginFolder)
                .distinct()
                .toList()
            return folders.ifEmpty { allSubFolders() }
        } catch (e: Exception) {
            println(e.message)
            return allSubFolders()
        }
    }

    private fun allSubFolders() = (path.getLocalAbsolutePath().toFile().listFiles() ?: arrayOf()).toList()

    companion object {
        /**
         * Like the IDE, this reads the descriptor either from the META-INF folder of the plugin or from one of the jars
         * in its lib folder. The plugins bundled with recent MPS versions only contain it inside the jars.
         */
        fun readPluginXml(pluginFolder: File): ByteArray? {
            val pluginXml = pluginFolder.resolve("META-INF").resolve("plugin.xml")
            if (pluginXml.isFile) return pluginXml.readBytes()

            val libFolder = pluginFolder.resolve("lib")
            // The MPS home folder isn't a plugin, even if one of its jars contains a plugin.xml (e.g. mps-workbench.jar)
            if (libFolder.resolve("mps-boot.jar").exists()) return null
            return (libFolder.listFiles() ?: emptyArray())
                .filter { it.isFile && it.extension == "jar" }
                .sortedBy { it.name }
                .firstNotNullOfOrNull { ZipUtil.unpackEntry(it, "META-INF/plugin.xml") }
        }

        fun isPluginFolder(folder: File): Boolean = readPluginXml(folder) != null

        fun fromPluginFolder(path: ModulePath): PluginModuleOwner {
            val pluginPath = path.getLocalAbsolutePath().toFile()
            val lines = if (pluginPath.isFile && pluginPath.extension == "jar") {
                val uri = URI("jar", pluginPath.toURI().toString(), null)
                FileSystems.newFileSystem(uri, mapOf<String, Any>(), null).use {
                    it.getPath("META-INF", "plugin.xml").readLines()
                }
            } else {
                val pluginXml = readPluginXml(pluginPath) ?: throw RuntimeException("No META-INF/plugin.xml found in $pluginPath")
                pluginXml.toString(Charsets.UTF_8).lines()
            }
            return fromPluginDescriptor(path, lines)
        }

        private fun fromPluginDescriptor(path: ModulePath, lines: List<String>): PluginModuleOwner {
            var pluginId: String? = null
            var name: String? = null
            val pluginDependencies: MutableSet<String> = HashSet()
            for (line in lines) {
                if (pluginId == null) {
                    val idMatch = Regex(""".*<id>(.+)</id>.*""").matchEntire(line)
                    if (idMatch != null) pluginId = idMatch.groupValues[1]
                }
                if (name == null) {
                    val nameMatch = Regex(""".*<name>(.+)</name>.*""").matchEntire(line)
                    if (nameMatch != null) name = nameMatch.groupValues[1]
                }
                val depends = Regex(""".*<depends>(.+)</depends>.*""").matchEntire(line)
                if (depends != null) pluginDependencies += depends.groupValues[1]
            }

            if (pluginId == null) {
                throw RuntimeException("Plugin has no ID: ${path.getLocalAbsolutePath()}")
            }
            return PluginModuleOwner(path, pluginId, name, pluginDependencies)
        }
    }
}
