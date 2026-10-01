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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Extracts the MPS distribution of [mpsVersion] from the Maven repository into [mpsHomeDir], if it doesn't exist yet,
 * and adjusts it, so it can be used as a local platform by the IntelliJ Platform Gradle Plugin.
 *
 * This happens during the configuration phase, because `intellijPlatform { local(...) }` requires the directory to
 * already exist.
 */
fun Project.copyMps(): File {
    val mpsHome = mpsHomeDir.get().asFile
    if (mpsHome.exists()) return mpsHome

    logger.lifecycle("Extracting MPS $mpsVersion ...")

    // Extract MPS during configuration phase, because using it in intellijPlatform.local requires it to already exist.
    // The distribution is resolved in the context of the calling project, because Gradle doesn't allow resolving
    // a configuration of another project (e.g. the root project) while this one is configured.
    val mpsZip = configurations.detachedConfiguration(dependencies.create("com.jetbrains:mps:$mpsVersion"))
    sync {
        from(zipTree(mpsZip.singleFile))
        into(mpsHomeDir)
    }

    // The IntelliJ gradle plugin doesn't search in jar files when reading plugin descriptors, but the IDE does.
    // Copy the XML files from the jars to the META-INF folders to fix that.
    for (pluginFolder in (mpsHomeDir.get().asFile.resolve("plugins").listFiles() ?: emptyArray())) {
        val jars = (pluginFolder.resolve("lib").listFiles() ?: emptyArray()).filter { it.extension == "jar" }
        for (jar in jars) {
            jar.inputStream().use {
                ZipInputStream(it).use { zip ->
                    val entries = generateSequence { zip.nextEntry }
                    for (entry in entries) {
                        if (entry.name.substringBefore("/") != "META-INF") continue
                        val outputFile = pluginFolder.resolve(entry.name)
                        if (outputFile.extension != "xml") continue
                        if (outputFile.exists()) {
                            logger.info("already exists: $outputFile")
                            continue
                        }
                        outputFile.parentFile.mkdirs()
                        outputFile.writeBytes(zip.readAllBytes())
                        logger.info("copied $outputFile")
                    }
                }
            }
        }

        // The IntelliJ Platform Gradle Plugin refuses to parse XML files with a DOCTYPE declaration (XXE protection)
        // and silently ignores such plugins. Many MPS plugin descriptors contain one. The copies are only read by the
        // Gradle plugin, the IDE reads the descriptors from the jars.
        for (descriptor in (pluginFolder.resolve("META-INF").listFiles() ?: emptyArray()).filter { it.extension == "xml" }) {
            val text = descriptor.readText()
            val withoutDoctype = text.replace(Regex("""<!DOCTYPE[^>]*>\s*"""), "")
            if (withoutDoctype != text) descriptor.writeText(withoutDoctype)
        }
    }

    completeProductInfo(mpsHome)
    provideModuleDescriptors(mpsHome)

    // The launch information in product-info.json is meant for launching the real IDE, but it's also used to
    // configure the forked test JVM. It's sanitized here, keeping the file present and valid (it also serves as the
    // PathManager marker).
    val productInfo = mpsHomeDir.get().asFile.resolve("product-info.json")
    if (productInfo.exists()) {
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(productInfo) as MutableMap<String, Any?>
        val launches = json["launch"] as? List<*> ?: emptyList<Any?>()
        for (launch in launches.filterIsInstance<MutableMap<String, Any?>>()) {
            // The plugin passes every line of the referenced .vmoptions file to the JVM verbatim, including the
            // "#Common IntelliJ Platform options:" comment lines. The launcher treats such a token as the main
            // class, so the -Djava.system.class.loader=com.intellij.util.lang.PathClassLoader it also sets cannot
            // be resolved (the -cp argfile after the token is ignored) and the VM aborts during initialization.
            // Strip comment and blank lines, keeping the real options (heap sizes, GC settings, ...).
            val vmOptionsPath = (launch["vmOptionsFilePath"] as? String)?.removePrefix("../")
            val vmOptionsFile = vmOptionsPath?.let { mpsHomeDir.get().asFile.resolve(it) }
            if (vmOptionsFile != null && vmOptionsFile.exists()) {
                val kept = vmOptionsFile.readLines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
                vmOptionsFile.writeText(kept.joinToString("\n", postfix = "\n"))
            }
        }

        // The Maven MPS distribution declares only a Linux/amd64 launch entry. The plugin refuses to
        // run on a host whose os.arch has no matching launch entry, which blocks running the IDE tests
        // locally on e.g. Apple Silicon. Add an entry for the current host (cloned from the existing
        // one, so it inherits the sanitized fields) when none matches. On CI (Linux/amd64) the entry
        // already exists, so this is a no-op.
        val mutableLaunches = launches.filterIsInstance<MutableMap<String, Any?>>()
        if (mutableLaunches.isNotEmpty()) {
            val osName = System.getProperty("os.name").lowercase()
            val currentOs = when {
                osName.contains("win") -> "Windows"
                osName.contains("mac") || osName.contains("darwin") -> "macOS"
                else -> "Linux"
            }
            val currentArch = System.getProperty("os.arch")
            val hasMatch = mutableLaunches.any { it["os"] == currentOs && it["arch"] == currentArch }
            if (!hasMatch) {
                @Suppress("UNCHECKED_CAST")
                val launchList = json["launch"] as MutableList<Any?>
                val hostLaunch = LinkedHashMap(mutableLaunches.first())
                hostLaunch["os"] = currentOs
                hostLaunch["arch"] = currentArch
                launchList.add(hostLaunch)
            }
        }

        productInfo.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(json)))
    }

    // The build number of a local IDE is expected to contain a product code, otherwise an exception is thrown.
    val buildTxt = mpsHomeDir.get().asFile.resolve("build.txt")
    val buildNumber = buildTxt.readText()
    val prefix = "MPS-"
    if (!buildNumber.startsWith(prefix)) {
        buildTxt.writeText("$prefix$buildNumber")
    }

    logger.lifecycle("Extracting MPS $mpsVersion done.")
    return mpsHome
}

/**
 * The IntelliJ Platform Gradle Plugin requires a product-info.json that lists the bundled plugins and their class
 * paths (the layout). The Maven distribution of MPS 2024.1 contains no product-info.json at all, and the ones of later
 * versions list neither the bundled plugins nor the layout. The missing parts are generated from the launcher script
 * and the plugin descriptors.
 */
private fun Project.completeProductInfo(mpsHome: File) {
    val productInfo = mpsHome.resolve("product-info.json")

    @Suppress("UNCHECKED_CAST")
    val json: MutableMap<String, Any?> = if (productInfo.exists()) {
        groovy.json.JsonSlurper().parse(productInfo) as MutableMap<String, Any?>
    } else {
        val launcherScript = mpsHome.resolve("bin/mps.sh").readText()
        linkedMapOf(
            "name" to "JetBrains MPS",
            "version" to mpsVersion,
            "buildNumber" to mpsHome.resolve("build.txt").readText().trim().removePrefix("MPS-"),
            "productCode" to "MPS",
            "envVarBaseName" to "MPS",
            "dataDirectoryName" to "MPS$mpsMajorVersion",
            "svgIconPath" to "bin/mps.svg",
            "productVendor" to "JetBrains",
            "launch" to mutableListOf(
                linkedMapOf(
                    "os" to "Linux",
                    "arch" to "amd64",
                    "launcherPath" to "bin/mps.sh",
                    "javaExecutablePath" to "jbr/bin/java",
                    "vmOptionsFilePath" to "bin/mps64.vmoptions",
                    "bootClassPathJarNames" to Regex("""\${'$'}IDE_HOME/lib/([^":]+\.jar)""")
                        .findAll(launcherScript).map { it.groupValues[1] }.toList(),
                    "additionalJvmArguments" to Regex("""--add-opens=\S+""").findAll(launcherScript).map { it.value }.toList(),
                    "mainClass" to (Regex("""MAIN_CLASS=(\S+)""").find(launcherScript)?.groupValues?.get(1) ?: "jetbrains.mps.Launcher"),
                ),
            ),
        )
    }

    if (json["layout"] == null) {
        // The plugin descriptors were copied out of the jars into the META-INF folders by copyMps.
        val plugins = (mpsHome.resolve("plugins").listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { pluginFolder ->
            val descriptor = pluginFolder.resolve("META-INF/plugin.xml").takeIf { it.isFile } ?: return@mapNotNull null
            val descriptorText = descriptor.readText()
            val pluginId = (Regex("<id>([^<]+)</id>").find(descriptorText) ?: Regex("<name>([^<]+)</name>").find(descriptorText))
                ?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val classPath = (pluginFolder.resolve("lib").listFiles() ?: emptyArray())
                .filter { it.extension == "jar" }
                .sortedBy { it.name }
                .map { it.relativeTo(mpsHome).invariantSeparatorsPath }
            pluginId to classPath
        }
        json["bundledPlugins"] = plugins.map { it.first }
        json["layout"] = plugins.map { (pluginId, classPath) -> linkedMapOf("name" to pluginId, "kind" to "plugin", "classPath" to classPath) }
    }
    if (json["modules"] == null) {
        json["modules"] = emptyList<String>()
    }

    productInfo.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(json)))
}

/**
 * The IntelliJ Platform Gradle Plugin expects the descriptors of the product modules in modules/module-descriptors.jar,
 * which isn't part of the MPS distribution. An empty one is sufficient, because we don't depend on any product modules
 * (`bundledModule`).
 */
private fun provideModuleDescriptors(mpsHome: File) {
    val moduleDescriptorsJar = mpsHome.resolve("modules/module-descriptors.jar")
    if (moduleDescriptorsJar.exists()) return
    moduleDescriptorsJar.parentFile.mkdirs()
    ZipOutputStream(moduleDescriptorsJar.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
        zip.write("Manifest-Version: 1.0\n".toByteArray())
        zip.closeEntry()
    }
}
