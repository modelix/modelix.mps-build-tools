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
package org.modelix.buildtools

data class ModuleDependency(val id: ModuleId, val moduleName: String?, val type: DependencyType, val ignoreIfMissing: Boolean) {
    constructor(idAndName: ModuleIdAndName, type: DependencyType, ignoreIfMissing: Boolean) : this(idAndName.id, idAndName.name, type, ignoreIfMissing)
    constructor(id: ModuleId, type: DependencyType, ignoreIfMissing: Boolean) : this(id, null, type, ignoreIfMissing)
}

enum class DependencyType {
    Classpath,
    Model,
    Generator,
    UseLanguageOrDevkit,
}
