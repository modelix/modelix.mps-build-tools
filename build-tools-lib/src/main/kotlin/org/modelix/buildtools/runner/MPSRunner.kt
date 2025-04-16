package org.modelix.buildtools.runner

import org.modelix.buildtools.BuildScriptGenerator
import org.modelix.buildtools.ModulesMiner
import org.modelix.buildtools.ProcessExecutor
import org.modelix.buildtools.buildXmlString
import org.modelix.buildtools.findExecutableAbsolutePath
import org.modelix.buildtools.newChild
import java.io.File
import java.util.Properties
import java.util.UUID

class MPSRunner(
    originalConfig: MPSRunnerConfig,
) {
    companion object {
        internal const val PROPERTY_KEY_ARTIFACTS_MPS = "artifacts.mps"

        // https://github.com/mbeddr/mps-build-backends/blob/b62bc815cf79d2c4af16ae38c2b0d98701c8f1db/launcher/src/main/java/de/itemis/mps/gradle/launcher/MpsBackendBuilder.java#L115
        private val jvmOpens = listOf(
            "java.base/java.io",
            "java.base/java.lang",
            "java.base/java.lang.reflect",
            "java.base/java.net",
            "java.base/java.nio",
            "java.base/java.nio.charset",
            "java.base/java.text",
            "java.base/java.time",
            "java.base/java.util",
            "java.base/java.util.concurrent",
            "java.base/java.util.concurrent.atomic",
            "java.base/jdk.internal.vm",
            "java.base/sun.nio.ch",
            "java.base/sun.nio.fs",
            "java.base/sun.security.ssl",
            "java.base/sun.security.util",
            "java.desktop/java.awt",
            "java.desktop/java.awt.dnd.peer",
            "java.desktop/java.awt.event",
            "java.desktop/java.awt.image",
            "java.desktop/java.awt.peer",
            "java.desktop/javax.swing",
            "java.desktop/javax.swing.plaf.basic",
            "java.desktop/javax.swing.text.html",
            "java.desktop/sun.awt.datatransfer",
            "java.desktop/sun.awt.image",
            "java.desktop/sun.awt",
            "java.desktop/sun.font",
            "java.desktop/sun.java2d",
            "java.desktop/sun.swing",
            "jdk.attach/sun.tools.attach",
            "jdk.compiler/com.sun.tools.javac.api",
            "jdk.internal.jvmstat/sun.jvmstat.monitor",
            "jdk.jdi/com.sun.tools.jdi",
            "java.desktop/sun.lwawt",
        )
        const val RUN_MPS_TASK_NAME = "run"
        private fun mpsHomeFromPropertiesOrEnv(): File? {
            return listOf("mps.home", "mps_home")
                .flatMap { listOf(it, it.uppercase()) }
                .let { it.map { System.getProperty(it) } + it.map { System.getenv(it) } }
                .filterNotNull()
                .filter { it.isNotEmpty() }
                .firstOrNull()
                ?.let { File(it) }
        }
    }

    private var config: MPSRunnerConfig = originalConfig

    private fun processConfig() {
        config = config.copy(
            classPathElements = config.classPathElements + config.jarFolders.flatMap {
                require(it.exists()) { "${it.absolutePath} doesn't exist" }
                if (it.isFile) {
                    listOf(it)
                } else {
                    it.walk().filter { it.extension == "jar" }.toList()
                }
            },
            mpsHome = config.mpsHome ?: mpsHomeFromPropertiesOrEnv(),
            moduleId = config.moduleId ?: UUID.randomUUID(),
            jvmArgs = config.jvmArgs + jvmOpens.map { "--add-opens=$it=ALL-UNNAMED" },
        )

        config.additionalModuleDependencyDirs.forEach { dir ->
            val miner = ModulesMiner()
            miner.searchInFolder(dir)
            miner.getModules().getModules().values.forEach {
                config = config.copy(
                    additionalModuleDependencies = config.additionalModuleDependencies +
                        it.idAndName.toString(),
                )
            }
            config = config.copy(additionalModuleDirs = config.additionalModuleDirs + dir)
        }
    }

    fun generateAll() {
        processConfig()
        generateSolution()
        generateAntScriptFile()
    }

    fun run() {
        generateAll()
        val ant = ProcessExecutor()
        ant.exec(listOf(findExecutableAbsolutePath("ant"), "-f", getAntScriptFile().canonicalPath))
    }

    private fun getMpsBuildPropertiesFile() = config.mpsHome!!.resolve("build.properties")
    private fun getMpsLanguagesDir() = config.mpsHome!!.resolve("languages")
    private fun getFileNamePrefix(): String = "runMPS_" + config.moduleId.toString().replace("-", "_")
    private fun getSolutionName(): String = getFileNamePrefix() + ".solution"
    fun getAntScriptFile(): File = getBuildDir().resolve(getFileNamePrefix() + ".ant.xml")
    private fun getSolutionDir(): File = getBuildDir().resolve(getSolutionName())
    fun getSolutionFile(): File = getSolutionDir().resolve("${getSolutionName()}.msd")
    private fun getBuildDir() = config.buildDir()

    private fun getMpsVersion(): String {
        val buildPropertiesFile = getMpsBuildPropertiesFile()
        require(buildPropertiesFile.exists()) { "MPS build.properties file not found: ${buildPropertiesFile.absolutePath}" }
        val buildProperties = Properties()
        buildPropertiesFile.inputStream().use { buildProperties.load(it) }

        return listOfNotNull(
            buildProperties["mpsBootstrapCore.version.major"],
            buildProperties["mpsBootstrapCore.version.minor"],
            // buildProperties["mpsBootstrapCore.version.bugfixNr"],
            buildProperties["mpsBootstrapCore.version.eap"],
        )
            .map { it.toString().trim('.') }
            .filter { it.isNotEmpty() }
            .joinToString(".")

//        mpsBootstrapCore.version.major=2020
//        mpsBootstrapCore.version.minor=3
//        mpsBootstrapCore.version.bugfixNr=.6
//        mpsBootstrapCore.version.eap=
//        mpsBootstrapCore.version=2020.3
    }

    private fun generateSolution() {
        val xml = buildXmlString {
            newChild("solution") {
                setAttribute("name", getSolutionName())
                setAttribute("uuid", config.moduleId.toString())
                setAttribute("moduleVersion", "0")
                setAttribute("pluginKind", "PLUGIN_OTHER")
                setAttribute("compileInMPS", "true")

                newChild("models") {}
                newChild("facets") {
                    newChild("facet") {
                        setAttribute("type", "java")
                    }
                }
                newChild("stubModelEntries") {
                    // The name is misleading. These entries are used for classloading and not for loading stub models,
                    // which would be specified in a modelRoot of type 'java_classes'.
                    for (jarLibrary in config.classPathElements) {
                        newChild("stubModelEntry") {
                            setAttribute("path", jarLibrary.absolutePath)
                        }
                    }
                }
                newChild("sourcePath") {}
                newChild("dependencies") {
                    newChild("dependency", "6354ebe7-c22a-4a0f-ac54-50b52ab9b065(JDK)")
                    newChild("dependency", "fdaaf35f-8ee3-4c37-b09d-9efaeaaa7a41(jetbrains.mps.core.tool.environment)")
                    newChild("dependency", "8865b7a8-5271-43d3-884c-6fd1d9cfdd34(MPS.OpenAPI)")
                    newChild("dependency", "6ed54515-acc8-4d1e-a16c-9fd6cfe951ea(MPS.Core)")
                    for (additionalModuleDependency in config.additionalModuleDependencies) {
                        newChild("dependency", additionalModuleDependency)
                    }
                }
                newChild("languageVersions") {}
                newChild("dependencyVersions") {}
            }
        }
        getSolutionFile().parentFile.mkdirs()
        getSolutionFile().writeText(xml)
    }

    fun generateAntScriptText(mpsVersion: String): String {
        val antLibs = BuildScriptGenerator.getMpsAntLibraries(mpsVersion)
        return buildXmlString {
            newChild("project") {
                setAttribute("name", getFileNamePrefix())
                setAttribute("default", RUN_MPS_TASK_NAME)

                newChild("property") {
                    setAttribute("name", "build.dir")
                    setAttribute("location", getBuildDir().absolutePath)
                }
                val mpsTmpDir = getBuildDir().parentFile.resolve(getBuildDir().name + "_tmp")
                newChild("property") {
                    setAttribute("name", "build.mps.config.path")
                    setAttribute("location", mpsTmpDir.resolve("config").absolutePath)
                }
                newChild("property") {
                    setAttribute("name", "build.mps.system.path")
                    setAttribute("location", mpsTmpDir.resolve("system").absolutePath)
                }
                newChild("property") {
                    setAttribute("name", "mps.home")
                    setAttribute("location", config.mpsHome!!.absolutePath)
                }
                newChild("property") {
                    setAttribute("name", PROPERTY_KEY_ARTIFACTS_MPS)
                    setAttribute("location", "\${mps.home}")
                }
                newChild("property") {
                    setAttribute("name", "environment")
                    setAttribute("value", "env")
                }
                newChild("property") {
                    setAttribute("name", "env.JAVA_HOME")
                    setAttribute("value", "\${java.home}")
                }
                newChild("property") {
                    setAttribute("name", "jdk.home")
                    setAttribute("value", "\${env.JAVA_HOME}")
                }
                newChild("path") {
                    setAttribute("id", "path.mps.ant.path")
                    for (antLib in antLibs) {
                        newChild("pathelement") {
                            setAttribute("location", config.mpsHome!!.resolve(antLib).absolutePath)
                        }
                    }
                }
                newChild("target") {
                    setAttribute("name", "declare-mps-tasks")

                    newChild("taskdef") {
                        setAttribute("resource", "jetbrains/mps/build/ant/antlib.xml")
                        setAttribute("classpathref", "path.mps.ant.path")
                    }
                }
                newChild("target") {
                    setAttribute("name", RUN_MPS_TASK_NAME)
                    setAttribute("depends", "declare-mps-tasks")

                    newChild("runMPS") {
                        setAttribute("solution", "${config.moduleId}(${getSolutionName()})")
                        setAttribute("startClass", config.mainClassName)
                        setAttribute("startMethod", config.mainMethodName)
                        if (config.autoPluginDiscovery != null) {
                            setAttribute("autoPluginDiscovery", config.autoPluginDiscovery.toString())
                        }

                        for (plugin in config.plugins) {
                            newChild("plugin") {
                                val pathXmlValue = when (plugin.path) {
                                    // We can use the forward slash as path separator for Windows,
                                    // because MPS also use it when generating for Windows.
                                    is BundledPluginPath -> "\${$PROPERTY_KEY_ARTIFACTS_MPS}/${plugin.path.dir}"
                                    is ExternalPluginPath -> plugin.path.dir.absolutePath
                                }
                                setAttribute("path", pathXmlValue)
                                setAttribute("id", plugin.id)
                            }
                        }

                        newChild("library") { setAttribute("file", getMpsLanguagesDir().absolutePath) }
                        newChild("library") { setAttribute("file", getSolutionDir().absolutePath) }
                        for (additionalModuleDir in config.additionalModuleDirs) {
                            newChild("library") { setAttribute("file", additionalModuleDir.absolutePath) }
                        }

                        newChild("jvmargs") {
                            newChild("arg") { setAttribute("value", "-Didea.config.path=${"$"}{build.mps.config.path}") }
                            newChild("arg") { setAttribute("value", "-Didea.system.path=${"$"}{build.mps.system.path}") }
                            newChild("arg") { setAttribute("value", "-ea") }
                            for (jvmArg in config.jvmArgs) {
                                newChild("arg") { setAttribute("value", jvmArg) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun generateAntScriptFile() {
        val mpsVersion = getMpsVersion()
        val antScriptText = generateAntScriptText(mpsVersion)
        val antScriptFile = getAntScriptFile()
        antScriptFile.parentFile.mkdirs()
        antScriptFile.writeText(antScriptText)
    }
}
