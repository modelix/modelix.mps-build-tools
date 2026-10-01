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
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MpsVersionsTest {

    private fun project(vararg properties: Pair<String, String>): Project {
        val project = ProjectBuilder.builder().build()
        properties.forEach { (key, value) -> project.extensions.extraProperties.set(key, value) }
        return project
    }

    @Test
    fun `platform version`() {
        assertEquals(241, "2024.1".toMpsPlatformVersion())
        assertEquals(243, "2024.3.2".toMpsPlatformVersion())
        assertEquals(251, "2025.1-RC1".toMpsPlatformVersion())
    }

    @Test
    fun `plugin compatibility range`() {
        assertEquals("241", MPS_PLUGIN_SINCE_BUILD)
        assertEquals("251.*", MPS_PLUGIN_UNTIL_BUILD)
    }

    @Test
    fun `default version`() {
        val project = project()
        assertEquals(DEFAULT_MPS_MAJOR_VERSION, project.mpsMajorVersion)
        assertEquals(SUPPORTED_MPS_VERSIONS.getValue(DEFAULT_MPS_MAJOR_VERSION), project.mpsVersion)
    }

    @Test
    fun `major version`() {
        val project = project("mps.version.major" to "2024.3")
        assertEquals("2024.3", project.mpsMajorVersion)
        assertEquals("2024.3", project.mpsVersion)
        assertEquals(243, project.mpsPlatformVersion)
        assertEquals(17, project.mpsJavaVersion)
    }

    @Test
    fun `full version`() {
        val project = project("mps.version" to "2025.1.4")
        assertEquals("2025.1", project.mpsMajorVersion)
        assertEquals("2025.1.4", project.mpsVersion)
        assertEquals(21, project.mpsJavaVersion)
    }

    @Test
    fun `unknown major version`() {
        assertFailsWith<IllegalArgumentException> { project("mps.version.major" to "2023.3").mpsVersion }
    }

    @Test
    fun `unsupported version`() {
        assertFailsWith<IllegalArgumentException> { project("mps.version" to "2023.2.3").mpsVersion }
    }

    @Test
    fun `home dir is shared with the root project`() {
        val root = project()
        val child = ProjectBuilder.builder().withParent(root).build()
        assertEquals(root.mpsHomeDir.get(), child.mpsHomeDir.get())
        assertEquals(root.layout.buildDirectory.dir("mps-${root.mpsVersion}").get(), root.mpsHomeDir.get())
    }
}
