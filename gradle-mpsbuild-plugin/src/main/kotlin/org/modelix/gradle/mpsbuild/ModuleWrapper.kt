package org.modelix.gradle.mpsbuild

import org.modelix.buildtools.FoundModules
import org.modelix.buildtools.ModuleId
import java.io.Serializable

class ModuleWrapper(
    var found: FoundModules = FoundModules(),
    var ignored: MutableSet<ModuleId> = mutableSetOf()) : Serializable