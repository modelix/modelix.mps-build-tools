package org.modelix.buildtools

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun buildStubsSolutionJar(body: IStubsSolutionBuilder.() -> Unit) {
    val builder = StubsSolutionBuilder().apply(body)
    val outputFolder = checkNotNull(builder.outputFolder) { "'outputFolder' not specified" }
    val outputFile = outputFolder.resolve(builder.solutionName + ".jar")
    val fileContent = StubsSolutionGenerator(builder).generateString()
    outputFolder.mkdirs()
    outputFile.outputStream().use { fileStream ->
        ZipOutputStream(fileStream).use { zip ->
            zip.putNextEntry(ZipEntry("modules/${builder.solutionName}/${builder.solutionName}.msd"))
            zip.bufferedWriter().use { it.write(fileContent) }
        }
    }
}

interface IStubsSolutionBuilder {
    /** Name of the generated solution */
    fun solutionName(name: String)

    /** UUID of the generated solution */
    fun solutionId(id: String)

    /** Required only for MPS versions before 2023.2. The IDEA plugin that contains the generated solution. */
    fun ideaPluginId(id: String)
    fun mpsVersion(version: String)

    /** Folder where the solution jar is written to */
    fun outputFolder(folder: File)

    /** MPS module that contains classes needed by this solution */
    fun moduleDependency(dep: ModuleIdAndName)

    /** Added as Kotlin stubs and to the class path */
    fun kotlinJar(path: String)

    /** Added as Java stubs and to the class path */
    fun javaJar(path: String)

    /** Jar in the MPS_HOME/lib folder that is added as Java stubs and loaded by the MPS class loader */
    fun javaJarFromMPS(path: String)

    /** Jar in the MPS_HOME/lib folder that is added as Kotlin stubs and loaded by the MPS class loader */
    fun kotlinJarFromMPS(path: String)

    /** Added to the classpath, but not as a stub model */
    fun classpathJar(path: String)

    /** Added as Java stubs, but not to the classpath */
    fun javaStubsJar(path: String)

    /** Added as Kotlin stubs, but not to the classpath */
    fun kotlinStubsJar(path: String)
}

class StubsSolutionBuilder : IStubsSolutionBuilder {
    var solutionName: String? = null
    var solutionId: String? = null
    var outputFolder: File? = null
    var ideaPluginId: String? = null
    var mpsVersion: String? = null
    val classPathJars = LinkedHashSet<String>()
    val kotlinStubsJars = LinkedHashSet<String>()
    val javaStubsJars = LinkedHashSet<String>()
    val moduleDependencies = ArrayList<ModuleIdAndName>()

    override fun solutionName(name: String) {
        this.solutionName = name
    }

    override fun solutionId(id: String) {
        this.solutionId = id
    }

    override fun ideaPluginId(id: String) {
        this.ideaPluginId = id
    }

    override fun mpsVersion(version: String) {
        mpsVersion = version
    }

    override fun kotlinJar(path: String) {
        val fullPath = if (path.contains("$")) path else "\${module}/../../../../lib/$path"
        this.kotlinStubsJars.add(fullPath)
        this.classPathJars.add(fullPath)
    }

    override fun javaJar(path: String) {
        val fullPath = if (path.contains("$")) path else "\${module}/../../../../lib/$path"
        this.javaStubsJars.add(fullPath)
        this.classPathJars.add(fullPath)
    }

    override fun javaJarFromMPS(path: String) {
        val fullPath = if (path.contains("$")) path else "\${mps_home}/lib/$path"
        this.javaStubsJars.add(fullPath)
    }

    override fun kotlinJarFromMPS(path: String) {
        val fullPath = if (path.contains("$")) path else "\${mps_home}/lib/$path"
        this.kotlinStubsJars.add(fullPath)
    }

    override fun classpathJar(path: String) {
        val fullPath = if (path.contains("$")) path else "\${module}/../../../../lib/$path"
        this.classPathJars.add(fullPath)
    }

    override fun javaStubsJar(path: String) {
        val fullPath = if (path.contains("$")) path else "\${module}/../../../../lib/$path"
        this.javaStubsJars.add(fullPath)
    }

    override fun kotlinStubsJar(path: String) {
        val fullPath = if (path.contains("$")) path else "\${module}/../../../../lib/$path"
        this.kotlinStubsJars.add(fullPath)
    }

    override fun outputFolder(folder: File) {
        this.outputFolder = folder
    }

    override fun moduleDependency(dep: ModuleIdAndName) {
        this.moduleDependencies.add(dep)
    }
}

class StubsSolutionGenerator(
    private val builder: StubsSolutionBuilder,
) {

    constructor(
        solutionIdAndName: ModuleIdAndName,
        jarPaths: List<String>,
        moduleDependencies: List<ModuleIdAndName>,
    ) : this(
        StubsSolutionBuilder().also { builder ->
            builder.solutionId(solutionIdAndName.id.id)
            solutionIdAndName.name?.let { builder.solutionName(it) }
            moduleDependencies.forEach { builder.moduleDependency(it) }
            jarPaths.forEach { builder.javaJar(it) }
        },
    )

    fun generateFile(solutionFile: File) {
        solutionFile.parentFile.mkdirs()
        solutionFile.writeText(generateString())
    }

    fun generateString(): String {
        val xml = newXmlDocument {
            newChild("solution") {
                setAttribute("name", checkNotNull(builder.solutionName) { "'solutionName' not specified" })
                setAttribute("pluginKind", "PLUGIN_OTHER")
                setAttribute("moduleVersion", "0")
                setAttribute("uuid", requireNotNull(builder.solutionId) { "'solutionId' not specified" })
                newChild("facets") {
                    newChild("facet") {
                        setAttribute("type", "java")
                        setAttribute("classes", "provided")
                        for (jar in builder.classPathJars) {
                            newChild("library") {
                                setAttribute("location", jar)
                            }
                        }
                    }
                    val ideaPluginId = builder.ideaPluginId
                    if (ideaPluginId != null) {
                        newChild("facet") {
                            setAttribute("type", "ideaPlugin")
                            setAttribute("pluginId", ideaPluginId)
                        }
                    }
                }
                newChild("models") {
                    for ((stubsType, typeSpecificPaths) in listOf("kotlin_jvm" to builder.kotlinStubsJars, "java_classes" to builder.javaStubsJars)) {
                        for ((folder, paths) in typeSpecificPaths.groupBy { it.substringBeforeLast("/", ".") }) {
                            newChild("modelRoot") {
                                setAttribute("type", stubsType)
                                setAttribute("contentPath", folder)
                                for (file in paths.map { it.substringAfterLast("/") }) {
                                    newChild("sourceRoot") {
                                        setAttribute("location", file)
                                    }
                                }
                            }
                        }
                    }
                }
                newChild("dependencies") {
                    newChild("dependency", "6354ebe7-c22a-4a0f-ac54-50b52ab9b065(JDK)") {
                        setAttribute("reexport", "true")
                    }
                    for (dep in builder.moduleDependencies) {
                        newChild("dependency", dep.toString()) {
                            setAttribute("reexport", "true")
                        }
                    }
                }
                newChild("stubModelEntries") {
                    for (jar in builder.classPathJars) {
                        newChild("stubModelEntry") {
                            setAttribute("path", jar)
                        }
                    }
                }
            }
        }
        return xmlToString(xml)
    }
}
