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
package org.modelix.gradle.mpsbuild

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.property
import org.modelix.buildtools.BuildScriptGenerator
import org.modelix.buildtools.CycleDetection
import org.modelix.buildtools.DependencyGraph
import org.modelix.buildtools.FoundModule
import org.modelix.buildtools.GraphWithCyclesVisitor
import org.modelix.buildtools.ModuleId
import org.modelix.buildtools.ModuleIdAndName
import org.modelix.buildtools.ModuleResolver
import org.modelix.buildtools.ModulesMiner
import org.modelix.buildtools.PublicationDependencyGraph
import org.modelix.buildtools.SourceModuleOwner
import org.modelix.buildtools.StubsSolutionGenerator
import org.modelix.buildtools.findExecutableAbsolutePath
import org.modelix.buildtools.invokelambda.InvokeLambda
import org.modelix.buildtools.modelixBuildToolsVersion
import org.modelix.buildtools.newChild
import org.modelix.buildtools.runner.MPSRunner
import org.modelix.buildtools.runner.MPSRunnerConfig
import org.zeroturnaround.zip.ZipUtil
import java.io.File
import java.io.ObjectOutputStream
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.io.path.toPath

class MPSBuildPlugin @Inject constructor(val project: Project) : Plugin<Project> {
    private val stubsPattern = Regex("stubs#([^#]+)#([^#]+)#([^#]+)")
    private val settings: MPSBuildSettings = project.extensions.create("mpsBuild", MPSBuildSettings::class.java)
    private val mpsDownloadDir = project.objects.directoryProperty()
        .convention(project.layout.buildDirectory.dir("mps"))
    private val mpsDirCurrent = project.objects.directoryProperty()
        .convention(settings.mpsHome_property.orElse(mpsDownloadDir.dir("current")))

    private val folder2owningDependency = HashMap<Path, ResolvedDependency>()

    lateinit var taskCopyDependencies: Task
    lateinit var taskGenerateAntScript: Task
    lateinit var taskCheckConfig: Task
    lateinit var taskLoadPomDependencies: Task
    lateinit var taskPackagePublications: Task

    private fun newTask(name: String): Task = project.task(name)

    private fun newTask(name: String, body: () -> Unit): Task {
        return project.task(name) {
            val action = Action<Task> { body() }
            this.actions = listOf(action)
        }
    }

    private fun taskBody(task: Task, body: () -> Unit) {
        val action = Action<Task> { body() }
        task.actions = listOf(action)
    }

    override fun apply(project: Project) {
        taskCopyDependencies = newTask("copyDependencies")
        taskGenerateAntScript = newTask("generateMpsAntScript")
        taskCheckConfig = newTask("checkMpsbuildConfig")
        taskLoadPomDependencies = newTask("loadPomDependencies")
        taskPackagePublications = newTask("packageMpsPublications")

        project.afterEvaluate {
            settings.validate()
            this@MPSBuildPlugin.afterEvaluate()
        }
    }

    private fun afterEvaluate() {
        val buildDir = project.layout.buildDirectory.dir("mpsbuild")
        val dependenciesDir = buildDir.map { it.dir("dependencies") }
        val publicationsDir = buildDir.map { it.dir("publications") }
        val antScriptFile = buildDir.map { it.file("build-modules.xml") }
        val publicationsVersion = getPublicationsVersion()
        val mavenPublications = HashMap<MPSBuildSettings.PublicationSettings, MavenPublication>()

        taskBody(taskCopyDependencies) {
            copyDependencies(settings.dependenciesConfig, dependenciesDir.get().asFile.normalize())
            if (settings.mpsHome == null) {
                val downloadedTo = checkNotNull(downloadMps(settings, mpsDownloadDir.get().asFile)) { "No MPS version or location specified" }
                if (mpsDirCurrent.get().asFile.exists()) {
                    mpsDirCurrent.get().asFile.deleteRecursively()
                }
                downloadedTo.copyRecursively(mpsDirCurrent.get().asFile, overwrite = true)
            }
        }
        settings.getTaskDependencies().forEach { taskCopyDependencies.dependsOn(it) }

        lateinit var generator: BuildScriptGenerator
        taskBody(taskGenerateAntScript) {
            val dirsToMine = setOfNotNull(dependenciesDir.get().asFile, mpsDirCurrent.get().asFile)
            generator = createBuildScriptGenerator(settings, project, buildDir.get().asFile, dirsToMine)
            generateAntScript(generator, antScriptFile.get().asFile)
        }
        taskGenerateAntScript.dependsOn(taskCopyDependencies)

        lateinit var publication2dnode: Map<MPSBuildSettings.PublicationSettings, DependencyGraph<FoundModule, ModuleId>.DependencyNode>
        lateinit var getPublication: (DependencyGraph<FoundModule, ModuleId>.DependencyNode) -> MPSBuildSettings.PublicationSettings?

        taskBody(taskCheckConfig) {
            val resolver = ModuleResolver(generator.modulesMiner.getModules(), generator.ignoredModules)
            val graph = PublicationDependencyGraph(resolver, emptyMap())
            val publication2modules = settings.getPublications().associateWith { resolvePublicationModules(it, resolver).toSet() }
            for (modulesA in publication2modules) {
                for (modulesB in publication2modules) {
                    if (modulesA.key == modulesB.key) continue
                    val modulesInBoth = modulesA.value.intersect(modulesB.value)
                    require(modulesInBoth.isEmpty()) {
                        "Modules found in publication ${modulesA.key.name} and ${modulesB.key.name}: ${modulesInBoth.map { it.name }.sorted()}"
                    }
                }
            }
            graph.load(publication2modules.values.flatten())
            val module2publication = publication2modules.flatMap { entry -> entry.value.map { it to entry.key } }.associate { it }

            getPublication = {
                it.modules.mapNotNull { module2publication[it] }.firstOrNull()
            }

            val checkCyclesBetweenPublications = {
                val cycleDetection = object : CycleDetection<DependencyGraph<FoundModule, ModuleId>.DependencyNode, MPSBuildSettings.PublicationSettings>() {
                    override fun getOutgoingEdges(element: DependencyGraph<FoundModule, ModuleId>.DependencyNode): Iterable<DependencyGraph<FoundModule, ModuleId>.DependencyNode> {
                        return element.getDependencies()
                    }

                    override fun getCategory(element: DependencyGraph<FoundModule, ModuleId>.DependencyNode): MPSBuildSettings.PublicationSettings? {
                        return getPublication(element)
                    }
                }
                cycleDetection.process(graph.getNodes())
                for (cycle in cycleDetection.cycles) {
                    val pubs = cycle.mapNotNull { getPublication(it) }.distinct()
                    if (pubs.size > 1) {
                        throw RuntimeException("Cycle between publications ${pubs.joinToString(" and ") { it.name } } probably caused by these modules: " + cycle.map { it.modules.map { it.name } })
                    }
                }
            }
            checkCyclesBetweenPublications()
            publication2dnode = publication2modules.entries.associate {
                it.key to graph.mergeElements(it.value)
            }
            checkCyclesBetweenPublications()

            val ensurePublicationsNotMerged: () -> Unit = {
                for (publicationA in publication2dnode) {
                    for (publicationB in publication2dnode) {
                        if (publicationA.key == publicationB.key) continue
                        require(publicationA.value.getMergedNode() != publicationB.value.getMergedNode()) {
                            "Unexpected merge of publications '${publicationA.key.name}' and '${publicationB.key.name}'"
                        }
                    }
                }
            }

            ensurePublicationsNotMerged()
            graph.mergeCycles()
            ensurePublicationsNotMerged()

            // merge nodes with exclusive direct dependency between them
            while (true) {
                var anyMerge = false
                for (n in graph.getNodes().filter { it.getReverseDependencies().size == 1 }) {
                    if (n.modules.all { it.owner !is SourceModuleOwner }) continue
                    val reverseDependencies = n.getReverseDependencies()
                    if (reverseDependencies.size != 1) continue // may have changed, because this loop modifies the graph
                    if (publication2dnode.values.map { it.getMergedNode() }.contains(n)) continue
                    graph.mergeNodes(n, reverseDependencies.first())
                    anyMerge = true
                }
                if (!anyMerge) break
            }

            ensurePublicationsNotMerged()

            for (node in graph.getNodes()) {
                val modules = node.modules
                    .filter { it.owner is SourceModuleOwner }
                    .map { it.name }
                    .filter { !it.startsWith("stubs#") }
                    .sorted()
                if (modules.isEmpty()) continue
                val publication = getPublication(node)
                require(publication != null) {
                    "Module $modules is used by multiple publications ${node.getReverseDependencies().mapNotNull(getPublication).map { it.name }}, but not part of any publication itself."
                }
//                println("Publication ${publication.name}")
//                for (module in modules) {
//                    println("    $module")
//                }
            }
        }
        taskCheckConfig.dependsOn(taskGenerateAntScript)

        val taskAssembleMpsModules = project.tasks.create("assembleMpsModules", Exec::class.java) {
            workingDir(buildDir.get())
            commandLine(antScriptFile.map { listOf(findExecutableAbsolutePath("ant"), "-f", it.asFile.absolutePath, "assemble") }.get())
            standardOutput = System.out
            errorOutput = System.err
            standardInput = System.`in`
            settings.javaHome?.let { environment("JAVA_HOME", it.absolutePath) }
        }
        taskAssembleMpsModules.dependsOn(taskGenerateAntScript)

        taskBody(taskLoadPomDependencies) {
            for (publication in settings.getPublications()) {
                val dnode = publication2dnode[publication]!!.getMergedNode()
                val pluginModuleNames = settings.getPluginModuleNames()
                val modulesAndStubs = dnode.modules.filter { !pluginModuleNames.contains(it.name) }
                val stubs = modulesAndStubs.filter { it.name.startsWith("stubs#") }.toSet()
                mavenPublications[publication]?.pom {
                    withXml {
                        asElement().newChild("dependencies") {
                            // dependencies between own publications
                            for (dependency in dnode.getDependencies().mapNotNull(getPublication)) {
                                newChild("dependency") {
                                    newChild("groupId", project.group.toString())
                                    newChild("artifactId", dependency.name.toValidPublicationName())
                                    newChild("version", publicationsVersion)
                                }
                            }

                            // dependencies to downloaded publications
                            val externalDependencies = (dnode.getDependencies() + dnode).flatMap { it.modules }
                                .mapNotNull { getOwningDependency(it.owner.path.getLocalAbsolutePath()) }
                                .distinct()
                            for (dependency in externalDependencies) {
                                newChild("dependency") {
                                    newChild("groupId", dependency.moduleGroup)
                                    newChild("artifactId", dependency.moduleName)
                                    newChild("version", dependency.moduleVersion)
                                }
                            }

                            // dependencies to java libraries
                            for (stub in stubs) {
                                val match = stubsPattern.matchEntire(stub.name)
                                    ?: throw RuntimeException("Failed to extract maven coordinates from ${stub.name}")
                                newChild("dependency") {
                                    newChild("groupId", match.groupValues[1])
                                    newChild("artifactId", match.groupValues[2])
                                    newChild("version", match.groupValues[3])
                                }
                            }
                        }
                    }
                }
            }
        }
        taskLoadPomDependencies.dependsOn(taskCheckConfig)

        taskBody(taskPackagePublications) {
            val packagedModulesDir = generator.getPackagedModulesDir()
            val generatedPlugins = generator.getGeneratedPlugins().entries.associate { it.key.name to it.value }
            val pluginModuleNames = settings.getPluginModuleNames()
            for (publication in settings.getPublications()) {
                val dnode = publication2dnode[publication]!!.getMergedNode()
                val modulesAndStubs = dnode.modules.filter { !pluginModuleNames.contains(it.name) }
                val stubs = modulesAndStubs.filter { it.name.startsWith("stubs#") }.toSet()
                val modules = modulesAndStubs - stubs
                val generatedFiles = modules.map { it.owner }.filterIsInstance<SourceModuleOwner>()
                    .distinct().flatMap { generator.getGeneratedFiles(it) }.map { it.absoluteFile.normalize() }
                val zipFile = publicationsDir.get().asFile.resolve("${publication.name}.zip")
                zipFile.parentFile.mkdirs()
                zipFile.outputStream().use { os ->
                    ZipOutputStream(os).use { zipStream ->
                        val packFile: (File, Path, Path) -> Unit = { file, path, parent ->
                            val relativePath = parent.relativize(path).toString()
                            require(!path.toString().startsWith("..") && !path.toString().contains("/../")) {
                                "$file expected to be inside $parent"
                            }
                            val entry = ZipEntry(relativePath)
                            zipStream.putNextEntry(entry)
                            file.inputStream().use { istream -> istream.copyTo(zipStream) }
                            zipStream.closeEntry()
                        }
                        for (file in generatedFiles) {
                            packFile(file, file.toPath(), packagedModulesDir.parentFile.toPath())
                        }
                        for (ideaPlugin in publication.ideaPlugins) {
                            val pluginFolder = generatedPlugins[ideaPlugin.getImplementationModuleName()]
                                ?: throw RuntimeException("Output for plugin '${ideaPlugin.getImplementationModuleName()}' not found")
                            for (file in pluginFolder.walk()) {
                                if (file.isFile) {
                                    packFile(file, file.toPath(), pluginFolder.parentFile.parentFile.toPath())
                                }
                            }
                        }
                    }
                }
            }

            println("Version $publicationsVersion ready for publishing")
        }
        taskPackagePublications.dependsOn(taskCheckConfig)
        taskPackagePublications.dependsOn(taskLoadPomDependencies)
        taskPackagePublications.dependsOn(taskAssembleMpsModules)
        taskAssembleMpsModules.mustRunAfter(taskCheckConfig) // fail fast

        val mpsPublicationsConfig = project.configurations.create("mpsPublications")
        val publishing = project.extensions.findByType(PublishingExtension::class.java)
        publishing?.publications {
            for (publicationData in settings.getPublications()) {
                create("_" + publicationData.name + "_", MavenPublication::class.java) {
                    val publication = this
                    mavenPublications[publicationData] = publication
                    publication.groupId = project.group.toString()
                    publication.artifactId = publicationData.name.toValidPublicationName()
                    publication.version = publicationsVersion

                    val zipFile = publicationsDir.get().asFile.resolve("${publicationData.name}.zip")
                    val artifact = project.artifacts.add(mpsPublicationsConfig.name, zipFile) {
                        type = "zip"
                        builtBy(taskPackagePublications)
                    }
                    publication.artifact(artifact)
                }
            }

            val parentPublicationName = settings.parentPublicationName
            if (parentPublicationName != null && settings.getPublications().isNotEmpty()) {
                create("_${parentPublicationName}_", MavenPublication::class.java) {
                    val publication = this
                    publication.groupId = project.group.toString()
                    publication.artifactId = parentPublicationName
                    publication.version = publicationsVersion
                    publication.pom {
                        withXml {
                            asElement().newChild("dependencies") {
                                for (publicationData in settings.getPublications()) {
                                    newChild("dependency") {
                                        newChild("groupId", project.group.toString())
                                        newChild("artifactId", publicationData.name.toValidPublicationName())
                                        newChild("version", publicationsVersion)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        project.tasks.withType(GenerateMavenPom::class.java).matching { it.name.matches(Regex(".+_.+_.+")) }.all {
            dependsOn(taskLoadPomDependencies)
        }

        val repositories = publishing?.repositories ?: listOf()
        val ownArtifactNames = settings.getPublications().map { it.name.toValidPublicationName() }.toSet() + "all"
        for (repo in repositories) {
            project.tasks.register("publishAllMpsPublicationsTo${repo.name.firstLetterUppercase()}Repository") {
                group = "publishing"
                description = "Publishes all Maven publications created by the mpsbuild plugin"
                dependsOn(
                    project.tasks.withType(PublishToMavenRepository::class.java).matching {
                        it.repository == repo && ownArtifactNames.contains(it.publication.artifactId)
                    },
                )
            }
        }
        project.tasks.register("publishAllMpsPublications") {
            group = "publishing"
            description = "Publishes all Maven publications created by the mpsbuild plugin"
            dependsOn(
                project.tasks.withType(PublishToMavenRepository::class.java).matching {
                    ownArtifactNames.contains(it.publication.artifactId)
                },
            )
        }

        if (settings.runConfigs.isNotEmpty()) {
            for (entry in settings.runConfigs) {
                val taskConfig = entry.value

                var config: Provider<MPSRunnerConfig> = taskConfig.configProperty
                config = config.map { initializeBuildAndWorkDir(entry.key, it) }

                config = config.map { config ->
                    if (config.mpsHome == null) {
                        config.copy(mpsHome = mpsDirCurrent.get().asFile)
                    } else {
                        config
                    }
                }

                val implementationLambda = taskConfig.implementationLambda
                val exportImplTask: TaskProvider<Task>? = if (implementationLambda != null) {
                    val implClass = implementationLambda::class.java

                    val resourceURI = implClass.getResourceName().let {
                        checkNotNull(implClass.getResource(it)) { "Resource not found: $it" }
                    }.toURI()
                    val scriptClassesFolderOrJar = if (resourceURI.scheme == "jar") {
                        URI.create(resourceURI.schemeSpecificPart.substringBefore("!")).toPath()
                    } else {
                        // This class is expected to be in a folder similar to ~/.gradle/caches/8.4/kotlin-dsl/scripts/742adf03631f28ed5a8bfbf1a7e7cb74/classes/
                        resourceURI.toPath().parent
                    }
                    taskConfig.classPathElement(scriptClassesFolderOrJar.toFile())

                    taskConfig.includeConfiguration(getOrCreateInvokeLambdaDependencies())

                    val serializedLambdaFile = config.map { it.buildDir().resolve(entry.key + "-impl.obj") }
                    config = config.map { config ->
                        config.copy(
                            mainClassName = InvokeLambda::class.java.name,
                            mainMethodName = InvokeLambda::invoke.name,
                            jvmArgs = config.jvmArgs + "-D${InvokeLambda.PROPERTY_KEY}=${serializedLambdaFile.get().absolutePath}",
                        )
                    }
                    project.tasks.register(entry.key + "_exportImpl") {
                        outputs.file(serializedLambdaFile)
                        doLast {
                            ObjectOutputStream(serializedLambdaFile.get().outputStream()).use {
                                it.writeObject(implementationLambda)
                            }
                        }
                    }
                } else {
                    null
                }

                config = config.map { config ->
                    if (taskConfig.classPathFromConfigurations.isNotEmpty()) {
                        val resolvedClasspath = taskConfig.classPathFromConfigurations
                            .flatMap { it.resolvedConfiguration.files }
                        config.copy(
                            classPathElements = config.classPathElements + resolvedClasspath,
                        )
                    } else {
                        config
                    }
                }

                createRunMPSTask(
                    taskName = entry.key,
                    config = config,
                    taskDependencies = listOfNotNull(exportImplTask, taskCopyDependencies).plus(taskConfig.taskDependencies).toTypedArray(),
                    taskSubclass = RunMPSTask::class.java,
                ).also {
                    it.configure {
                        result = taskConfig.result
                        outputs.file(config.get().buildDir().resolve(InvokeLambda.RESULT_FILE_NAME))
                    }
                }
            }
        }
    }

    private fun initializeBuildAndWorkDir(taskName: String, config: MPSRunnerConfig): MPSRunnerConfig {
        var config = config
        if (config.buildDir == null) {
            config = config.copy(
                buildDir = project.layout.buildDirectory.asFile.get().resolve("runMPS").resolve(taskName),
            )
        }
        if (config.workDir == null) {
            config = config.copy(workDir = config.buildDir)
        }
        return config
    }

    fun createRunMPSTask(
        taskName: String,
        config: MPSRunnerConfig,
        taskDependencies: Array<Any> = emptyArray(),
    ): TaskProvider<RunMPSTask> {
        return createRunMPSTask(
            taskName,
            project.objects.property<MPSRunnerConfig>().also { it.set(config) },
            taskDependencies,
            RunMPSTask::class.java,
        )
    }

    fun <TaskT : RunMPSTask> createRunMPSTask(
        taskName: String,
        config: MPSRunnerConfig,
        taskDependencies: Array<Any> = emptyArray(),
        taskSubclass: Class<TaskT>,
    ): TaskProvider<TaskT> {
        return createRunMPSTask(
            taskName,
            project.objects.property<MPSRunnerConfig>().also { it.set(config) },
            taskDependencies,
            taskSubclass,
        )
    }

    fun <TaskT : RunMPSTask> createRunMPSTask(
        taskName: String,
        config: Provider<MPSRunnerConfig>,
        taskDependencies: Array<Any> = emptyArray(),
        taskSubclass: Class<TaskT>,
    ): TaskProvider<TaskT> {
        var config = config.map { initializeBuildAndWorkDir(taskName, it) }

        config = config.map { config ->
            if (config.moduleId == null) {
                val moduleIdFile = config.buildDir().resolve("$taskName.moduleId.txt")
                config.copy(
                    moduleId = try {
                        UUID.fromString(moduleIdFile.readText())
                    } catch (ex: Exception) {
                        moduleIdFile.parentFile.mkdirs()
                        UUID.randomUUID().also { moduleIdFile.writeText(it.toString()) }
                    },
                )
            } else {
                config
            }
        }

        config = config.map { config ->
            if (config.mpsHome == null) {
                config.copy(mpsHome = mpsDirCurrent.get().asFile)
            } else {
                config
            }
        }

        val generateTask = project.tasks.register(taskName + "_generate", GenerateMPSRunnerFilesTask::class.java) {
            val task = this
            dependsOn(*taskDependencies)
            task.dependsOn(taskCopyDependencies)
            task.config.set(config)

            outputs.file(config.map { MPSRunner(it).getAntScriptFile() })
            outputs.file(config.map { MPSRunner(it).getSolutionFile() })
        }

        return project.tasks.register(taskName, taskSubclass) {
            val task = this

            task.config.set(config)

            task.dependsOn(generateTask)

            inputs.dir(config.map { it.buildDir() })
            config.get().additionalModuleDirs.forEach { inputs.dir(it) }
            config.get().additionalModuleDependencyDirs.forEach { inputs.dir(it) }
            config.get().jarFolders.forEach { inputs.dir(it) }
            config.get().classPathElements.forEach { if (it.isFile || it.extension.lowercase() == "jar") inputs.file(it) else inputs.dir(it) }

            task.workingDir(config.map { it.workDir() })
            task.mainClass.set("org.apache.tools.ant.launch.Launcher")
            task.classpath(getOrCreateAntDependencies())
            task.args("-buildfile", MPSRunner(config.get()).getAntScriptFile())
            task.args(MPSRunner.RUN_MPS_TASK_NAME)
            task.standardInput = System.`in`
            task.standardOutput = System.out
            task.errorOutput = System.err
        }
    }

    private fun getOrCreateAntDependencies(): Configuration {
        val configName = "runMPS-ant-dependencies"
        val antDependencies = project.configurations.findByName(configName)?.let { return it }
            ?: project.configurations.create(configName)
        with(project) {
            dependencies {
                antDependencies("org.apache.ant:ant-junit:1.10.12")
            }
        }
        return antDependencies
    }

    private fun getOrCreateInvokeLambdaDependencies(): Configuration {
        val configName = "runMPS-invoke-lambda-dependencies"
        val antDependencies = project.configurations.findByName(configName)?.let { return it }
            ?: project.configurations.create(configName)
        with(project) {
            dependencies {
                antDependencies("org.modelix.mps:build-tools-invoke-lambda:$modelixBuildToolsVersion")
            }
        }
        return antDependencies
    }

    private fun getPublicationsVersion() = if (project.version == Project.DEFAULT_VERSION) {
        null
    } else {
        ("" + project.version).ifEmpty { null }
    } ?: generateVersionNumber(settings.mpsMajorVersion)

    private fun downloadMps(settings: MPSBuildSettings, targetDir: File): File? {
        var mpsDir: File? = null
        settings.mpsDependenciesConfig?.resolvedConfiguration?.lenientConfiguration?.let {
            for (file in it.files) {
                val targetFile = targetDir.resolve(file.name)
                if (!targetFile.exists()) {
                    copyAndUnzip(file, targetFile)
                }
                mpsDir = targetFile
            }
        }

        if (mpsDir == null) {
            val url = settings.getMpsDownloadUrl()
            if (url != null) {
                val file = targetDir.resolve(url.toString().substringAfterLast("/"))
                if (!file.exists()) {
                    println("Downloading $url")
                    file.parentFile.mkdirs()
                    url.openStream().use { istream ->
                        file.outputStream().use { ostream ->
                            istream.copyTo(ostream)
                        }
                    }
                }
                if (file.isFile) {
                    ZipUtil.explode(file)
                }
                mpsDir = file
            }
        }

        return mpsDir
    }

    private fun generateVersionNumber(mpsVersion: String?): String {
        val timestamp = SimpleDateFormat("yyyyMMddHHmm").format(Date())
        val version = if (mpsVersion == null) timestamp else "$mpsVersion-$timestamp"
        println("##teamcity[buildNumber '$version']")
        return version
    }

    private fun generateStubsSolution(dependency: ResolvedDependency, stubsDir: File) {
        val solutionName = getStubSolutionName(dependency)
        StubsSolutionGenerator(
            solutionIdAndName = ModuleIdAndName(ModuleId("~$solutionName"), solutionName),
            jarPaths = dependency.moduleArtifacts.map { it.file }.filter { it.extension == "jar" }.map { it.absolutePath }.distinct(),
            moduleDependencies = dependency.children.map { getStubSolutionName(it) }.map { ModuleIdAndName(ModuleId("~$solutionName"), solutionName) },
        ).generateFile(stubsDir.resolve(solutionName).resolve("$solutionName.msd"))
    }

    private fun String.toValidPublicationName() = replace(Regex("[^A-Za-z0-9_\\-.]"), "_").toLowerCase()

    private fun getStubSolutionName(dependency: ResolvedDependency): String {
//                        val clean: (String)->String = { it.replace(Regex("[^a-zA-Z0-9]"), "_") }
//                        val group = clean(it.moduleGroup)
//                        val artifactId = clean(it.moduleName)
//                        val version = clean(it.moduleVersion)
//                        "stubs.$group.$artifactId.$version"
        return "stubs#" + dependency.module.id.toString().replace(":", "#")
    }

    private fun ResolvedConfiguration.getAllDependencies(): List<ResolvedDependency> {
        val allDependencies: MutableList<ResolvedDependency> = ArrayList()
        object : GraphWithCyclesVisitor<ResolvedDependency>() {
            override fun onVisit(element: ResolvedDependency) {
                allDependencies.add(element)
                visit(element.children)
            }
        }.visit(firstLevelModuleDependencies)
        return allDependencies
    }

    private fun generateAntScript(generator: BuildScriptGenerator, antScriptFile: File): BuildScriptGenerator {
        val xml = generator.generateXML()
        antScriptFile.parentFile.mkdirs()
        antScriptFile.writeText(xml)
        return generator
    }

    private fun createBuildScriptGenerator(
        settings: MPSBuildSettings,
        project: Project,
        buildDir: File,
        dependencyFiles: Set<File>,
    ): BuildScriptGenerator {
        val modulesMiner = ModulesMiner()
        for (modulePath in settings.resolveModulePaths(project.projectDir.toPath())) {
            modulesMiner.searchInFolder(modulePath.toFile())
        }
        for (dependencyFile in dependencyFiles) {
            modulesMiner.searchInFolder(dependencyFile)
        }
        val mpsPath = settings.mpsHome
        if (mpsPath != null) {
            val mpsHome = project.projectDir.toPath().resolve(Paths.get(mpsPath)).normalize().toFile()
            if (!mpsHome.exists()) {
                throw RuntimeException("$mpsHome doesn't exist")
            }
            if (!dependencyFiles.contains(mpsHome)) {
                modulesMiner.searchInFolder(mpsHome)
            }
        }
        val resolver = ModuleResolver(modulesMiner.getModules(), emptySet())
        val modulesToGenerate = settings.getPublications()
            .flatMap { resolvePublicationModules(it, resolver) }.map { it.moduleId }
        val generator = BuildScriptGenerator(
            modulesMiner = modulesMiner,
            modulesToGenerate = modulesToGenerate,
            ignoredModules = emptySet(),
            initialMacros = settings.getMacros(project.projectDir.toPath()),
            buildDir = buildDir,
        )
        generator.generatorHeapSize = settings.generatorHeapSize
        generator.ideaPlugins += settings.getPublications().flatMap { it.ideaPlugins }.map { pluginSettings ->
            val moduleName = pluginSettings.getImplementationModuleName()
            val module = (
                modulesMiner.getModules().getModules().values.find { it.name == moduleName }
                    ?: throw RuntimeException("module $moduleName not found")
                )
            BuildScriptGenerator.IdeaPlugin(module, "" + project.version, pluginSettings.pluginXml)
        }
        return generator
    }

    private fun resolvePublicationModules(publication: MPSBuildSettings.PublicationSettings, resolver: ModuleResolver): List<FoundModule> {
        val modulesToGenerate: MutableList<FoundModule> = ArrayList()
        val includedPaths = publication.resolveIncludedModules(project.projectDir.toPath())
        val includedModuleNames = publication.getIncludedModuleNames()
        val foundModuleNames: MutableSet<String> = HashSet()
        if (includedPaths != null || includedModuleNames != null) {
            for (module in resolver.availableModules.getModules().values) {
                if (includedModuleNames != null && includedModuleNames.contains(module.name)) {
                    modulesToGenerate.add(module)
                    foundModuleNames.add(module.name)
                } else if (includedPaths != null) {
                    val modulePath = module.owner.path.getLocalAbsolutePath()
                    if (includedPaths.any(modulePath::startsWith)) {
                        modulesToGenerate.add(module)
                    }
                }
            }
        }

        val missingModuleNames = includedModuleNames?.minus(foundModuleNames)?.sorted()
            ?: emptyList()

        if (missingModuleNames.isNotEmpty()) {
            throw RuntimeException("Modules not found: $missingModuleNames")
        }
        return modulesToGenerate
    }

    private fun copyDependencies(dependenciesConfiguration: Configuration, targetFolder: File) {
        val dependencies = dependenciesConfiguration.resolvedConfiguration.getAllDependencies()
        for (dependency in dependencies) {
            val files = dependency.moduleArtifacts.map { it.file }
            for (file in files) {
                copyDependency(file, targetFolder, dependency)
            }
        }
    }

    private fun copyDependency(file: File, targetFolder: File, dependency: ResolvedDependency) {
        when (file.extension) {
            "jar" -> {
                generateStubsSolution(dependency, targetFolder.resolve("stubs"))
            }
            "zip" -> {
                val targetFile = targetFolder
                    .resolve("modules")
                    .resolve(dependency.moduleGroup)
                    .resolve(dependency.moduleName)
                // .resolve(file.name)
                folder2owningDependency[targetFile.absoluteFile.toPath().normalize()] = dependency
                copyAndUnzip(file, targetFile)
            }
            else -> println("Ignored file $file from dependency ${dependency.module.id}")
        }
    }

    private fun getOwningDependency(file: Path): ResolvedDependency? {
        var f: Path? = file.toAbsolutePath().normalize()
        while (f != null) {
            val owner = folder2owningDependency[f]
            if (owner != null) return owner
            f = f.parent
        }
        return null
    }

    private fun copyAndUnzip(sourceFile: File, targetFile: File) {
        targetFile.parentFile.mkdirs()
        if (sourceFile.extension == "zip") {
            if (targetFile.exists()) targetFile.deleteRecursively()
            ZipUtil.unpack(sourceFile, targetFile)
        } else {
            sourceFile.copyTo(targetFile, true)
        }
    }
}

private fun String.firstLetterUppercase() = if (isEmpty()) this else substring(0, 1).toUpperCase() + drop(1)

private fun Class<*>.getResourceName() = "/${name.replace(".", "/")}.class"
