package org.modelix.gradle.mpsbuild

import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import org.modelix.buildtools.*
import javax.inject.Inject

abstract class CheckConfig @Inject constructor(of: ObjectFactory): DefaultTask() {

    @Input
    val moduleWrapper: Property<ModuleWrapper> = of.property()

    @Input
    val settings: Property<MPSBuildSettings> = of.property(MPSBuildSettings::class.java)

    @Internal
    val publication2dnode: MapProperty<PublicationSettings, DependencyGraph<FoundModule, ModuleId>.DependencyNode> =
        of.mapProperty()

    @Internal
    val getPublication: Property<(DependencyGraph<FoundModule, ModuleId>.DependencyNode)->PublicationSettings?> = of.property()

    @TaskAction
    fun check() {
        val resolver = ModuleResolver(moduleWrapper.get().found, moduleWrapper.get().ignored)
        val graph = PublicationDependencyGraph(resolver, emptyMap())
        val publication2modules = settings.get().getPublications().associateWith { resolvePublicationModules(it, resolver).toSet() }
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

        getPublication.set {
            it.modules.mapNotNull { module2publication[it] }.firstOrNull()
        }

        val checkCyclesBetweenPublications = {
            val cycleDetection = object : CycleDetection<DependencyGraph<FoundModule, ModuleId>.DependencyNode, PublicationSettings>() {
                override fun getOutgoingEdges(element: DependencyGraph<FoundModule, ModuleId>.DependencyNode): Iterable<DependencyGraph<FoundModule, ModuleId>.DependencyNode> {
                    return element.getDependencies()
                }

                override fun getCategory(element: DependencyGraph<FoundModule, ModuleId>.DependencyNode): PublicationSettings? {
                    return getPublication.get()(element)
                }
            }
            cycleDetection.process(graph.getNodes())
            for (cycle in cycleDetection.cycles) {
                val pubs = cycle.mapNotNull { getPublication.get()(it) }.distinct()
                if (pubs.size > 1) {
                    throw RuntimeException("Cycle between publications ${pubs.joinToString(" and ") { it.name } } probably caused by these modules: " + cycle.map { it.modules.map { it.name } })
                }
            }
        }
        checkCyclesBetweenPublications()
        publication2dnode.set(publication2modules.entries.associate {
            it.key to graph.mergeElements(it.value)
        })
        checkCyclesBetweenPublications()

        val publication2dnode = publication2dnode.get()

        val ensurePublicationsNotMerged: ()->Unit = {
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
            val publication = getPublication.get()(node)
            require(publication != null) {
                "Module $modules is used by multiple publications ${node.getReverseDependencies().mapNotNull(getPublication.get()).map { it.name }}, but not part of any publication itself."
            }
//                println("Publication ${publication.name}")
//                for (module in modules) {
//                    println("    $module")
//                }
        }
    }

    private fun resolvePublicationModules(publication: PublicationSettings, resolver: ModuleResolver): List<FoundModule> {
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
}