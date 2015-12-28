/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.scala;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ScalaBuckConfig {

  private final BuckConfig delegate;

  public ScalaBuckConfig(final BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Tool getScalac(BuildRuleResolver resolver) {
    CommandTool.Builder scalac = new CommandTool.Builder(findScalac(resolver));

    // Add some standard options.
    scalac.addArg("-target:" + delegate.getValue("scala", "target_level").or("jvm-1.7"));

    if (delegate.getBooleanValue("scala", "optimize", false)) {
      scalac.addArg("-optimize");
    }

    return scalac.build();
  }

  BuildTarget getScalaLibraryTarget() {
    return delegate.getRequiredBuildTarget("scala", "library");
  }

  Optional<BuildTarget> getScalacTarget() {
    return delegate.getMaybeBuildTarget("scala", "compiler");
  }

  ImmutableList<String> getCompilerFlags() {
    return ImmutableList.copyOf(
        Splitter.on(" ").omitEmptyStrings().split(
            delegate.getValue("go", "compiler_flags").or("")));
  }

  private Tool findScalac(BuildRuleResolver resolver) {
    Optional<Tool> configScalac = delegate.getTool("scala", "compiler", resolver);
    if (configScalac.isPresent()) {
      return configScalac.get();
    }

    Optional<Path> externalScalac = new ExecutableFinder().getOptionalExecutable(
        Paths.get("scalac"), delegate.getEnvironment());
    if (externalScalac.isPresent()) {
      return new HashedFileTool(externalScalac.get());
    }

    String scalaHome = delegate.getEnvironment().get("SCALA_HOME");
    if (scalaHome != null) {
      Path scalacInHomePath = Paths.get(scalaHome, "bin", "scalac");
      if (scalacInHomePath.toFile().exists()) {
        return new HashedFileTool(scalacInHomePath);
      }
    }

    throw new HumanReadableException("Could not find scalac. Consider setting scala.compiler.");
  }
}
