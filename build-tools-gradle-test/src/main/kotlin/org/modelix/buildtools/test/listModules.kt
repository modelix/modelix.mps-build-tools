package org.modelix.buildtools.test

import jetbrains.mps.smodel.MPSModuleRepository

fun listModules() {
    val repo = MPSModuleRepository.getInstance()
    repo.modelAccess.runReadAction {
        repo.modules.forEach { println("Module: ${it.moduleName}") }
    }
}
