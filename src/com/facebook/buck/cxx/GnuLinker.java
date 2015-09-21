/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.io.FileScrubber;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * A specialization of {@link Linker} containing information specific to the GNU implementation.
 */
public class GnuLinker implements Linker {

  private final Tool tool;

  public GnuLinker(Tool tool) {
    this.tool = tool;
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    return tool.getDeps(resolver);
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return tool.getInputs();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return tool.getCommandPrefix(resolver);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers(Path linkingDirectory) {
    return ImmutableList.of();
  }

  @Override
  public Iterable<String> linkWhole(String input) {
    return ImmutableList.of("-Wl,--whole-archive", input, "-Wl,--no-whole-archive");
  }

  @Override
  public Iterable<String> soname(String arg) {
    return Linkers.iXlinker("-soname", arg);
  }

  @Override
  public String origin() {
    return "$ORIGIN";
  }

  @Override
  public String libOrigin() {
    return "$ORIGIN";
  }

  @Override
  public String searchPathEnvVar() {
    return "LD_LIBRARY_PATH";
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder
        .setReflectively("tool", tool)
        .setReflectively("type", getClass().getSimpleName());
  }

}
