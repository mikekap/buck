/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.ImmutableProcessExecutorParams;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Represents the command line options that should be passed to scalac. Note that the options do not
 * include either the classpath or the directory for storing class files.
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class ScalacOptions implements RuleKeyAppendable {

  protected abstract Optional<ProcessExecutor> getProcessExecutor();
  protected abstract Optional<BuildTarget> getLibraryDep();

  protected abstract Optional<SourcePath> getScalacPath();

  @Value.Default
  protected boolean isProductionBuild() {
    return false;
  }

  @Value.Default
  protected boolean isVerbose() {
    return false;
  }

  @VisibleForTesting
  abstract String getTargetLevel();

  public abstract List<String> getExtraArguments();
  protected abstract Optional<String> getBootclasspath();

  protected boolean isDebug() {
    return !isProductionBuild();
  }

  public Scalac getScalac(SourcePathResolver resolver) {
    if (!getScalacPath().isPresent()) {
      throw new RuntimeException("Could not find scalac");
    }

    Path externalScalac = resolver.getPath(getScalacPath().get());
    if (!getProcessExecutor().isPresent()) {
      throw new RuntimeException("Misconfigured ScalacOptions --- no process executor");
    }

    ImmutableList<String> command;
    if (externalScalac.toString().endsWith(".jar")) {
      command = ImmutableList.of("java", "-Dscala.usejavacp=true", "-jar", externalScalac.toString());
    } else {
      command = ImmutableList.of(externalScalac.toString());
    }

    ImmutableList.Builder<String> commandWithVersion = ImmutableList.builder();
    commandWithVersion.addAll(command);
    commandWithVersion.add("-version");

    ProcessExecutorParams params = ImmutableProcessExecutorParams.builder()
        .setCommand(commandWithVersion.build())
        .build();
    ProcessExecutor.Result result = null;
    try {
      result = getProcessExecutor().get().launchAndExecute(params);
    } catch (InterruptedException | IOException e) {
      throw new RuntimeException(e);
    }

    Optional<ScalacVersion> version;
    Optional<String> stderr = result.getStderr();
    if (Strings.isNullOrEmpty(stderr.orNull())) {
      version = Optional.absent();
    } else {
      version = Optional.of((ScalacVersion) ImmutableScalacVersion.of(stderr.get()));
    }
    return new ExternalScalac(command, version);
  }

  public void appendOptionsToList(ImmutableList.Builder<String> optionsBuilder) {

    // Add some standard options.
    optionsBuilder.add("-target:" + getTargetLevel());
    optionsBuilder.add("-nobootcp");

    if (isProductionBuild()) {
      optionsBuilder.add("-optimize");
    }

    if (isDebug()) {
      optionsBuilder.add("-g:vars");
    }

    if (isVerbose()) {
      optionsBuilder.add("-verbose");
    }

    // Override the bootclasspath if Buck is building Scala code for Android.
    if (getBootclasspath().isPresent()) {
      optionsBuilder.add("-javabootclasspath", getBootclasspath().get());
    }

    // Add extra arguments.
    optionsBuilder.addAll(getExtraArguments());
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder, String key) {
    // TODO(simons): Include bootclasspath params.
    builder
        .setReflectively(key + ".targetLevel", getTargetLevel())
        .setReflectively(key + ".extraArguments", Joiner.on(',').join(getExtraArguments()))
        .setReflectively(key + ".production", isProductionBuild())
        .setReflectively(key + ".debug", isDebug())
        .setReflectively(key + ".scalac", getScalacPath());

    return builder;
  }

  public static ImmutableScalacOptions.Builder builder() {
    return ImmutableScalacOptions.builder();
  }

  public static ImmutableScalacOptions.Builder builderWithDefaultOptions(ProcessExecutor processExecutor, BuckConfig config) {
      return ImmutableScalacOptions.builder()
          .setProcessExecutor(processExecutor)
          .setTargetLevel("jvm-1.7")
          .setScalacPath(config.getSourcePath("scala", "compiler"))
          .setLibraryDep(config.getBuildTarget("scala", "library"));
  }

  public static ImmutableScalacOptions.Builder builder(ScalacOptions options) {
    Preconditions.checkNotNull(options);

    ImmutableScalacOptions.Builder builder = ImmutableScalacOptions.builder();

    builder.setVerbose(options.isVerbose());
    builder.setProductionBuild(options.isProductionBuild());

    builder.setProcessExecutor(options.getProcessExecutor());
    builder.setLibraryDep(options.getLibraryDep());
    builder.setScalacPath(options.getScalacPath());
    builder.setBootclasspath(options.getBootclasspath());
    builder.setTargetLevel(options.getTargetLevel());
    builder.addAllExtraArguments(options.getExtraArguments());

    return builder;
  }
}
