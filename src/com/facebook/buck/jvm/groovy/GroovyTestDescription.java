/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.logging.Level;

public class GroovyTestDescription implements Description<GroovyTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("groovy_test");

  private final GroovyBuckConfig groovyBuckConfig;
  private final JavacOptions defaultJavacOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final Optional<Path> testTempDirOverride;

  public GroovyTestDescription(
      GroovyBuckConfig groovyBuckConfig,
      JavacOptions defaultOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      Optional<Path> testTempDirOverride) {
    this.groovyBuckConfig = groovyBuckConfig;
    this.defaultJavacOptions = defaultOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.testTempDirOverride = testTempDirOverride;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> JavaTest createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    BuildTarget abiJarTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(CalculateAbi.FLAVOR)
            .build();

    GroovycToJarStepFactory stepFactory = new GroovycToJarStepFactory(
        groovyBuckConfig.getGroovyCompiler().get(),
        args.extraGroovycArguments,
        JavacOptionsFactory.create(
            defaultJavacOptions,
            params,
            resolver,
            pathResolver,
            args
        ));

    JavaTest test =
        resolver.addToIndex(
            new JavaTest(
                params.appendExtraDeps(
                    pathResolver.filterBuildRuleInputs(
                        defaultJavacOptions.getInputs(pathResolver))),
                pathResolver,
                args.srcs.get(),
                ResourceValidator.validateResources(
                    pathResolver,
                    params.getProjectFilesystem(),
                    args.resources.get()),
                defaultJavacOptions.getGeneratedSourceFolderName(),
                args.labels.get(),
                args.contacts.get(),
                Optional.<SourcePath>absent(),
                new BuildTargetSourcePath(abiJarTarget),
                /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
                args.testType.or(TestType.JUNIT),
                stepFactory,
                args.vmArgs.get(),
                ImmutableMap.<String, String>of(),
                ImmutableSet.<BuildRule>of(),
                Optional.<Path>absent(),
                args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
                args.getRunTestSeparately(),
                args.stdOutLogLevel,
                args.stdErrLogLevel,
                testTempDirOverride));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(test.getBuildTarget())));

    return test;
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JvmLibraryArg {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;
    public Optional<ImmutableList<String>> extraGroovycArguments;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;

    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<ImmutableList<String>> vmArgs;
    public Optional<TestType> testType;
    public Optional<Boolean> runTestSeparately;
    public Optional<Level> stdErrLogLevel;
    public Optional<Level> stdOutLogLevel;
    public Optional<Long> testRuleTimeoutMs;

    public boolean getRunTestSeparately() {
      return runTestSeparately.or(false);
    }
  }
}
