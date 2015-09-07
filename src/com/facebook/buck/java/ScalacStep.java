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
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command used to compile scala libraries with a variety of ways to handle dependencies.
 */
public class ScalacStep implements Step {

  private final Path outputDirectory;

  private final Optional<Path> workingDirectory;

  private final ImmutableSet<Path> scalaSourceFilePaths;

  private final Optional<Path> pathToSrcsList;

  private final ScalacOptions scalacOptions;

  private final ImmutableSet<Path> declaredClasspathEntries;

  private final BuildTarget invokingRule;

  private final Optional<SuggestBuildRules> suggestBuildRules;

  private final SourcePathResolver resolver;

  private final ProjectFilesystem filesystem;

  /**
   * Will be {@code true} once {@link Scalac#buildWithClasspath(ExecutionContext, ProjectFilesystem,
   * SourcePathResolver, BuildTarget, ImmutableList, ImmutableSet, Optional, Optional)} has been
   * invoked.
   */
  private AtomicBoolean isExecuted = new AtomicBoolean(false);

  private static final Pattern IMPORT_FAILURE =
      Pattern.compile("import ([\\w\\.\\*]*);");

  private static final Pattern PACKAGE_FAILURE =
      Pattern.compile(".*?package ([\\w\\.\\*]*) does not exist");

  private static final Pattern ACCESS_FAILURE =
      Pattern.compile(".*?error: cannot access ([\\w\\.\\*]*)");

  private static final Pattern CLASS_NOT_FOUND =
      Pattern.compile(".*?class file for ([\\w\\.\\*]*) not found");

  private static final Pattern CLASS_SYMBOL_NOT_FOUND =
      Pattern.compile(".*?symbol:\\s*class\\s*([\\w\\.\\*]*)");

  private static final ImmutableList<Pattern> MISSING_IMPORT_PATTERNS =
      ImmutableList.of(
          IMPORT_FAILURE,
          PACKAGE_FAILURE,
          ACCESS_FAILURE,
          CLASS_NOT_FOUND,
          CLASS_SYMBOL_NOT_FOUND);

  private static final String LINE_SEPARATOR = System.getProperty("line.separator");

  public static interface SuggestBuildRules {
    public ImmutableSet<String> suggest(ProjectFilesystem filesystem,
        ImmutableSet<String> failedImports);
  }

  public ScalacStep(
      Path outputDirectory,
      Optional<Path> workingDirectory,
      Set<Path> scalaSourceFilePaths,
      Optional<Path> pathToSrcsList,
      Set<Path> declaredClasspathEntries,
      ScalacOptions scalacOptions,
      BuildTarget invokingRule,
      Optional<SuggestBuildRules> suggestBuildRules,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem) {
    this.outputDirectory = outputDirectory;
    this.workingDirectory = workingDirectory;
    this.scalaSourceFilePaths = ImmutableSet.copyOf(scalaSourceFilePaths);
    this.pathToSrcsList = pathToSrcsList;
    this.scalacOptions = scalacOptions;

    this.declaredClasspathEntries = ImmutableSet.copyOf(declaredClasspathEntries);
    this.invokingRule = invokingRule;
    this.suggestBuildRules = suggestBuildRules;
    this.resolver = resolver;
    this.filesystem = filesystem;
  }

  @Override
  public final int execute(ExecutionContext context) throws IOException, InterruptedException {
    try {
      return tryBuildWithFirstOrderDeps(context, filesystem);
    } finally {
      isExecuted.set(true);
    }
  }

  private int tryBuildWithFirstOrderDeps(ExecutionContext context, ProjectFilesystem filesystem)
      throws InterruptedException, IOException {
    try (
        CapturingPrintStream stdout = new CapturingPrintStream();
        CapturingPrintStream stderr = new CapturingPrintStream();
        ExecutionContext firstOrderContext = context.createSubContext(stdout, stderr)) {

      Scalac scalac = getScalac();

      int declaredDepsResult = scalac.buildWithClasspath(
          firstOrderContext,
          filesystem,
          resolver,
          invokingRule,
          getOptions(context, declaredClasspathEntries),
          scalaSourceFilePaths,
          pathToSrcsList,
          workingDirectory);

      String firstOrderStdout = stdout.getContentsAsString(Charsets.UTF_8);
      String firstOrderStderr = stderr.getContentsAsString(Charsets.UTF_8);

      if (declaredDepsResult != 0 && suggestBuildRules.isPresent()) {
        ImmutableSet<String> failedImports = findFailedImports(firstOrderStderr);
        ImmutableSet<String> suggestions = suggestBuildRules.get().suggest(
            filesystem,
            failedImports);

        ImmutableList.Builder<String> errorMessage = ImmutableList.builder();
        errorMessage.add(firstOrderStderr);

        if (!suggestions.isEmpty()) {
          String invoker = invokingRule.toString();
          errorMessage.add(String.format("Rule %s has failed to build.", invoker));
          errorMessage.add(Joiner.on(LINE_SEPARATOR).join(failedImports));
          errorMessage.add("Try adding the following deps:");
          errorMessage.add(Joiner.on(LINE_SEPARATOR).join(suggestions));
          errorMessage.add("");
          errorMessage.add("");
        }

        context.getStdOut().print(firstOrderStdout);
        context.getStdErr().println(Joiner.on("\n").join(errorMessage.build()));
      } else if (declaredDepsResult != 0) {
        context.getStdOut().print(firstOrderStdout);
        context.getStdErr().print(firstOrderStderr);
      }

      return declaredDepsResult;
    }
  }

  @VisibleForTesting
  Scalac getScalac() {
    return scalacOptions.getScalac();
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return getScalac().getDescription(
        getOptions(context, getClasspathEntries()),
        scalaSourceFilePaths,
        pathToSrcsList);
  }

  @Override
  public String getShortName() {
    return getScalac().getShortName();
  }

  @VisibleForTesting
  static ImmutableSet<String> findFailedImports(String output) {
    Iterable<String> lines = Splitter.on(LINE_SEPARATOR).split(output);
    ImmutableSortedSet.Builder<String> failedImports = ImmutableSortedSet.naturalOrder();
    for (String line : lines) {
      for (Pattern missingImportPattern : MISSING_IMPORT_PATTERNS) {
        Matcher lineMatch = missingImportPattern.matcher(line);
        if (lineMatch.matches()) {
          failedImports.add(lineMatch.group(1));
          break;
        }
      }
    }
    return failedImports.build();
  }

  /**
   * Returns a list of command-line options to pass to scalac.  These options reflect
   * the configuration of this scalac command.
   *
   * @param context the ExecutionContext with in which scalac will run
   * @return list of String command-line options.
   */
  @VisibleForTesting
  ImmutableList<String> getOptions(
      ExecutionContext context,
      Set<Path> buildClasspathEntries) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();

    scalacOptions.appendOptionsToList(builder);

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      builder.add("-verbose");
    }

    // Specify the output directory.
    Function<Path, Path> pathRelativizer = filesystem.getAbsolutifier();
    builder.add("-d").add(pathRelativizer.apply(outputDirectory).toString());

    // Build up and set the classpath.
    if (!buildClasspathEntries.isEmpty()) {
      String classpath = Joiner.on(File.pathSeparator).join(
          FluentIterable.from(buildClasspathEntries)
              .transform(pathRelativizer));
      builder.add("-classpath", classpath);
    } else {
      builder.add("-classpath", "''");
    }

    return builder.build();
  }

  /**
   * @return The classpath entries used to invoke scalac.
   */
  @VisibleForTesting
  ImmutableSet<Path> getClasspathEntries() {
    return declaredClasspathEntries;
  }

  @VisibleForTesting
  Set<Path> getSrcs() {
    return scalaSourceFilePaths;
  }
}
