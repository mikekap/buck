/*
 * Copyright 2016-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

public class WorkerTool extends AbstractBuildRule
    implements HasRuntimeDeps {

  private final BinaryBuildRule exe;
  private final String args;

  protected WorkerTool(
      BuildRuleParams ruleParams,
      SourcePathResolver resolver,
      BinaryBuildRule exe,
      String args) {
    super(ruleParams, resolver);
    this.exe = exe;
    this.args = args;
  }

  public BinaryBuildRule getBinaryBuildRule() {
    return this.exe;
  }

  public Sha1HashCode getHash() {
    return Sha1HashCode.of(getProjectFilesystem().readFirstLine(getPathToOutput()).get());
  }

  public String getArgs() {
    return this.args;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .add(exe)
        .addAll(exe.getExecutableCommand().getDeps(getResolver()))
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Hasher hasher = Hashing.sha1().newHasher();
    for (BuildRule rule : getRuntimeDeps()) {
      hasher.putUnencodedChars(rule.getFullyQualifiedName());
      try {
        hasher.putUnencodedChars(getProjectFilesystem().computeSha1(rule.getPathToOutput()));
      } catch (IOException e) {
        context.logError(e, "Error hashing input rule: %s", rule);
        return ImmutableList.of();
      }
    }

    String inputsHash = hasher.hash().toString();
    return ImmutableList.<Step>of(
        new WriteFileStep(
            getProjectFilesystem(),
            inputsHash,
            getPathToOutput(),
            /* executable */ false));
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return BuildTargets.getScratchPath(getBuildTarget(), "__%s__runtime_hash");
  }
}
