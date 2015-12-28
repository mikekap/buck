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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.SuggestBuildRules;
import com.facebook.buck.jvm.java.BaseCompileToJarStepFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

class ScalacToJarStepFactory extends BaseCompileToJarStepFactory {

  private final Tool scalac;
  private final ImmutableList<String> extraArguments;

  public ScalacToJarStepFactory(
      Tool scalac,
      ImmutableList<String> extraArguments) {
    this.scalac = scalac;
    this.extraArguments = extraArguments;
  }

  @Override
  public void createCompileStep(
      BuildContext context,
      ImmutableSortedSet<Path> sourceFilePaths,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<Path> classpathEntries,
      Path outputDirectory,
      Optional<Path> workingDirectory,
      Optional<Path> pathToSrcsList,
      Optional<SuggestBuildRules> suggestBuildRules,
      /* out params */
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext) {
    steps.add(
        new ScalacStep(
            scalac,
            extraArguments,
            resolver,
            outputDirectory,
            sourceFilePaths,
            classpathEntries,
            filesystem));
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    scalac.appendToRuleKey(builder);
    return builder.setReflectively("extraArguments", extraArguments);
  }
}
