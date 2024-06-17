package org.modelix.buildtools.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class MPSRunnerTest {
    val mpsVersion = "2023.3.1"
    val buildDir = File("/buildDir")
    val mpsHome = File("/mpsHome")

    @Test
    fun `generate config with bundled plugins`() {
        val plugins = listOf(PluginConfig("aPluginId", BundledPluginPath(File("plugins/aPluginFolder"))))
        val config = MPSRunnerConfig(buildDir = buildDir, mpsHome = mpsHome, plugins = plugins)
        val runner = MPSRunner(config)

        val antScriptText = runner.generateAntScriptText(mpsVersion)

        assertThat(antScriptText)
            .contains("""<plugin id="aPluginId" path="${'$'}{artifacts.mps}/plugins/aPluginFolder"/>""")
    }

    @Test
    fun `bundled plugin cannot be created with an absolute path`() {
        val exception = assertThrows<IllegalArgumentException> {
            BundledPluginPath(File("/plugins/aPluginFolder"))
        }

        assertThat(exception)
            .hasMessage("The path `/plugins/aPluginFolder` to a bundled plugin must be a relative path.")
    }

    @Test
    fun `generate config with external plugins`() {
        val plugins = listOf(PluginConfig("aPluginId", ExternalPluginPath(File("/aPluginFolder"))))
        val config = MPSRunnerConfig(buildDir = buildDir, mpsHome = mpsHome, plugins = plugins)
        val runner = MPSRunner(config)

        val antScriptText = runner.generateAntScriptText(mpsVersion)

        assertThat(antScriptText)
            .contains("""<plugin id="aPluginId" path="/aPluginFolder"/>""")
    }
}
