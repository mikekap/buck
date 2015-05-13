/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java;


import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.zip.Unzip;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class ExternalScalac implements Scalac {

  private static final ScalacVersion DEFAULT_VERSION = ScalacVersion.of("unknown version");

  private final ImmutableList<String> scalacCmd;
  private final Optional<ScalacVersion> version;

  public ExternalScalac(ImmutableList<String> scalacCmd, Optional<ScalacVersion> version) {
    this.scalacCmd = scalacCmd;
    this.version = version;
  }

  @Override
  public ScalacVersion getVersion() {
    return version.or(DEFAULT_VERSION);
  }

  @Override
  public String getDescription(
      ExecutionContext context,
      ImmutableList<String> options,
      ImmutableSet<Path> scalaSourceFilePaths,
      Optional<Path> pathToSrcsList) {
    StringBuilder builder = new StringBuilder();
    Joiner.on(" ").appendTo(builder, scalacCmd);
    builder.append(" ");
    Joiner.on(" ").appendTo(builder, options);
    builder.append(" ");

    if (pathToSrcsList.isPresent()) {
      builder.append("@").append(pathToSrcsList.get());
    } else {
      Joiner.on(" ").appendTo(builder, scalaSourceFilePaths);
    }

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return Joiner.on(" ").join(scalacCmd);
  }

  @Override
  public boolean isUsingWorkspace() {
    return true;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder, String key) {
    if (version.isPresent()) {
      return builder.setReflectively(key + ".scalac.version", version.get().toString());
    }
    return builder.setReflectively(key + ".scalac", getShortName());
  }

  @Override
  public int buildWithClasspath(
      ExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableSet<Path> scalaSourceFilePaths,
      Optional<Path> pathToSrcsList,
      Optional<Path> workingDirectory) throws InterruptedException {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.addAll(scalacCmd);
    command.addAll(options);

    ImmutableList<Path> expandedSources;
    try {
      expandedSources = getExpandedSourcePaths(
          context,
          invokingRule,
          scalaSourceFilePaths,
          workingDirectory);
    } catch (IOException e) {
      throw new HumanReadableException(
          "Unable to expand sources for %s into %s",
          invokingRule,
          workingDirectory);
    }
    if (pathToSrcsList.isPresent()) {
      try {
        context.getProjectFilesystem().writeLinesToPath(
            FluentIterable.from(expandedSources)
                .transform(Functions.toStringFunction())
                .transform(ARGFILES_ESCAPER),
            pathToSrcsList.get());
        command.add("@" + pathToSrcsList.get());
      } catch (IOException e) {
        context.logError(
            e,
            "Cannot write list of .scala files to compile to %s file! Terminating compilation.",
            pathToSrcsList.get());
        return 1;
      }
    } else {
      for (Path source : expandedSources) {
        command.add(source.toString());
      }
    }

    ProcessBuilder processBuilder = new ProcessBuilder(command.build());

    // Set environment to client environment and add additional information.
    Map<String, String> env = processBuilder.environment();
    env.clear();
    env.putAll(context.getEnvironment());
    env.put("BUCK_INVOKING_RULE", invokingRule.toString());
    env.put("BUCK_TARGET", invokingRule.toString());
    env.put("BUCK_DIRECTORY_ROOT", context.getProjectDirectoryRoot().toString());

    processBuilder.directory(context.getProjectDirectoryRoot().toAbsolutePath().toFile());
    // Run the command
    int exitCode = -1;
    try {
      ProcessExecutor.Result result = context.getProcessExecutor().execute(processBuilder.start());
      exitCode = result.getExitCode();
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return exitCode;
    }

    return exitCode;
  }

  private ImmutableList<Path> getExpandedSourcePaths(
      ExecutionContext context,
      BuildTarget invokingRule,
      ImmutableSet<Path> scalaSourceFilePaths,
      Optional<Path> workingDirectory) throws IOException {
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    // Add sources file or sources list to command
    ImmutableList.Builder<Path> sources = ImmutableList.builder();
    for (Path path : scalaSourceFilePaths) {
      if (path.toString().endsWith(".scala")) {
        sources.add(path);
      } else if (path.toString().endsWith(SRC_ZIP)) {
        if (!workingDirectory.isPresent()) {
          throw new HumanReadableException(
              "Attempting to compile target %s which specified a .src.zip input %s but no " +
                  "working directory was specified.",
              invokingRule.toString(),
              path);
        }
        // For a Zip of .scala files, create a JavaFileObject for each .java entry.
        ImmutableList<Path> zipPaths = Unzip.extractZipFile(
            projectFilesystem.resolve(path),
            projectFilesystem.resolve(workingDirectory.get()),
            /* overwriteExistingFiles */ true);
        sources.addAll(
            FluentIterable.from(zipPaths)
                .filter(
                    new Predicate<Path>() {
                      @Override
                      public boolean apply(Path input) {
                        return input.toString().endsWith(".scala");
                      }
                    }));
      }
    }
    return sources.build();
  }
}
