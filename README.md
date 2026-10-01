# The Modelix Project

The modelix project develops an open source platform for (meta-)models on the web. We are native to the web and the cloud.

For general information on modelix, please refer to the [official modelix homepage](https://modelix.org) as well as the [platform documentation](https://docs.modelix.org).

For individual component specific documentation, see https://docs.modelix.org/modelix/latest/reference/components.html


# `modelix.mps-build-tools`

This repository contains `mps-build-tools` which can be used as a replacement for the MPS build language.
These components are used internally by modelix but can also be applied externally.

## Gradle plugins for MPS plugins

`mps-platform-gradle` provides two Gradle plugins that set up the
[IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
with MPS as the platform. They support MPS 2024.1 and newer.

- `org.modelix.mps.platform` compiles against MPS and runs the tests with MPS.
  MPS is downloaded from the [itemis Maven repository](https://artifacts.itemis.cloud/repository/maven-mps/)
  and extracted into the build directory of the root project. The plugin declares that repository itself.
  Because the plugin adds project repositories, the repositories of `dependencyResolutionManagement` in the settings
  aren't used anymore and have to be declared in the project, too.
- `org.modelix.mps.plugin` additionally builds an MPS plugin that is compatible with all supported MPS versions
  and registers the task `installMpsPlugin`, if an MPS installation is found.
  Call `publishMpsPlugin()` to publish the plugin zip.

The MPS version is selected with the Gradle property `mps.version.major` (e.g. `2024.3`) or `mps.version`
(e.g. `2024.3.2`). The plugins folder for `installMpsPlugin` can be specified with `mps.plugins.dir`
or `mps<platform version>.plugins.dir` (e.g. `mps243.plugins.dir`).

The IntelliJ Platform Gradle Plugin is a dependency of these plugins, so don't declare it with a version yourself.
Helpers like `publishMpsPlugin()`, `copyMps()`, `mpsHomeDir`, `excludeMPSLibraries` and `includeMetaInfFolder()` are available in
`org.modelix.gradle.mpsplatform`.


# Authors

Development of modelix is supported by [itemis](https://itemis.com)


# Copyright and License

Copyright © 2021-present by the modelix open source project and the individual contributors. All Rights Reserved.

Use of this software is granted under the terms of the Apache License Version 2.0.
See the [LICENSE](LICENSE) to find the full license text.
