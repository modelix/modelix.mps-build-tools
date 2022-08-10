package org.modelix.buildtools

import org.modelix.buildtools.modulepersistence.DevkitDescriptor
import org.modelix.buildtools.modulepersistence.LanguageDescriptor

/**
 * Calculates and aggregates dependencies of a module. Adapted from jetbrains.mps.build.mps.util.RuntimeDependencies.
 *
 * Note that since this class operates on actual module descriptors rather than their imported images in build scripts,
 * it can produce different (but more correct) results if the build script contains extra dependencies or runtimes.
 *
 * (MPS does not report extra dependencies in build scripts as errors, only missing ones.)
 */
class RuntimeDependenciesCollector constructor(private val resolver: ModuleResolver) {

    fun collectFor(module: FoundModule): RuntimeDependencies {
        val usedLanguagesAndDevkits = module.getLanguageOrDevkitUsedInModels().resolveAll(module)

        val devkitDescriptors = usedLanguagesAndDevkits.filter { it.moduleType == ModuleType.Devkit }
            .includingExtendedDevkits()
            .map { (it.moduleDescriptor as DevkitDescriptor) }

        val devkitLanguages = devkitDescriptors.flatMap { it.exportedLanguages }.resolveAll(module)
        val devkitSolutions = devkitDescriptors.flatMap { it.exportedSolutions }.resolveAll(module)

        val usedLanguages = usedLanguagesAndDevkits
            .filter { it.moduleType == ModuleType.Language }
            .plus(devkitLanguages)
            .toSet()

        // Don't want to find out RTs of extended languages at execution time, record them at once.
        // Besides, we care about RTs state the moment code was generated, if newer language version decides to change
        // RT, deployed module won't get affected.
        val langRuntimes = HashSet<FoundModule>()
        for (lang in usedLanguages.includingExtendedLanguages()) {
            langRuntimes.addAll(
                (lang.moduleDescriptor as LanguageDescriptor).runtime.map { it.idAndName }.resolveAll(lang)
            )
        }

        val compileDeps = HashSet<FoundModule>()
        compileDeps.addAll(devkitSolutions)
        compileDeps.addAll(module.moduleDescriptor!!.moduleDependencies.map { it.idAndName }.resolveAll(module))

        return RuntimeDependencies(usedLanguages, langRuntimes, compileDeps)
    }

    private fun Iterable<FoundModule>.includingExtendedDevkits(): Iterable<FoundModule> =
        Iterable {
            object : RecursiveIterator<FoundModule>(iterator()) {
                override fun children(node: FoundModule): Iterator<FoundModule> =
                    (node.moduleDescriptor as DevkitDescriptor).extendedDevkits.resolveAll(node).iterator()
            }
        }

    private fun Iterable<FoundModule>.includingExtendedLanguages(): Iterable<FoundModule> =
        Iterable {
            object : RecursiveIterator<FoundModule>(iterator()) {
                override fun children(node: FoundModule): Iterator<FoundModule> =
                    (node.moduleDescriptor as LanguageDescriptor).extendedLanguages.resolveAll(node).iterator()
            }
        }

    private fun Iterable<ModuleIdAndName>.resolveAll(parent: FoundModule): List<FoundModule> =
        map { resolver.resolveModule(it, parent)!! }
}

data class RuntimeDependencies(
    val usedLanguages: Set<FoundModule>,
    val languageRuntimes: Set<FoundModule>,
    val deploymentDependencies: Set<FoundModule>
) {
    companion object {
        fun forModule(module: FoundModule, resolver: ModuleResolver): RuntimeDependencies =
            RuntimeDependenciesCollector(resolver).collectFor(module)
    }
}
