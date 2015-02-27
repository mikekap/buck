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

package com.facebook.buck.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FlavorableDescription;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import java.nio.file.Path;
import java.util.List;

public class ScalaLibraryDescription implements Description<ScalaLibraryDescription.Arg>,
                                     FlavorableDescription<ScalaLibraryDescription.Arg>,
                                     Flavored, ImplicitDepsInferringDescription<ScalaLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("scala_library");

  @VisibleForTesting
  final ScalacOptions defaultOptions;

  public ScalaLibraryDescription(ScalacOptions defaultOptions) {
    this.defaultOptions = defaultOptions;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return flavors.equals(ImmutableSet.of(JavaLibrary.SRC_JAR)) || flavors.isEmpty();
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = params.getBuildTarget();

    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.

    if (target.getFlavors().contains(JavaLibrary.SRC_JAR)) {
      throw new RuntimeException("Not implemented - scala source jars");
      //return new ScalaSourceJar(params, pathResolver, args.srcs.get());
    }

    ImmutableScalacOptions scalacOptions = ScalaLibraryDescription.getScalacOptions(
        args,
        defaultOptions).build();

    return new DefaultScalaLibrary(
        params,
        pathResolver,
        args.srcs.get(),
        validateResources(pathResolver, args, params.getProjectFilesystem()),
        args.proguardConfig,
        args.postprocessClassesCommands.get(),
        resolver.getAllRules(args.exportedDeps.get()),
        resolver.getAllRules(args.providedDeps.get()),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        scalacOptions,
        resolver.getRule(scalacOptions.getLibraryDep().get()),
        args.resourcesRoot);
  }

  /**
   * Collect implicit deps for the scala compiler and scala library.
   */
  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Arg args) {
    List<BuildTarget> deps = Lists.newArrayList();

    ImmutableScalacOptions scalacOptions = ScalaLibraryDescription.getScalacOptions(
        args,
        defaultOptions).build();

    // Add the compiler target, if there is one.
    SourcePath compiler = scalacOptions.getScalacPath().get();
    if (compiler instanceof BuildTargetSourcePath) {
      deps.add(((BuildTargetSourcePath) compiler).getTarget());
    }

    if (scalacOptions.getLibraryDep().isPresent()) {
      deps.add(scalacOptions.getLibraryDep().get());
    }
    return deps;
  }

  // TODO(natthu): Consider adding a validateArg() method on Description which gets called before
  // createBuildable().
  public static ImmutableSortedSet<SourcePath> validateResources(
      SourcePathResolver resolver,
      Arg arg,
      ProjectFilesystem filesystem) {
    for (Path path : resolver.filterInputsToCompareToOutput(arg.resources.get())) {
      if (!filesystem.exists(path)) {
        throw new HumanReadableException("Error: `resources` argument '%s' does not exist.", path);
      } else if (filesystem.isDirectory(path)) {
        throw new HumanReadableException(
            "Error: a directory is not a valid input to the `resources` argument: %s",
            path);
      }
    }
    return arg.resources.get();
  }

  public static ImmutableScalacOptions.Builder getScalacOptions(
      Arg args,
      ScalacOptions defaultOptions) {
    ImmutableScalacOptions.Builder builder = ScalacOptions.builder(defaultOptions);

    if (args.target.isPresent()) {
      builder.setTargetLevel(args.target.get());
    }

    if (args.extraArguments.isPresent()) {
      builder.addAllExtraArguments(args.extraArguments.get());
    }

    if (args.scalac.isPresent()) {
      builder.setScalacPath(args.scalac);
    }

    return builder;
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;
    public Optional<String> target;
    public Optional<SourcePath> scalac;
    public Optional<ImmutableList<String>> extraArguments;
    public Optional<Path> proguardConfig;
    public Optional<ImmutableList<String>> postprocessClassesCommands;
    public Optional<Path> resourcesRoot;

    public Optional<ImmutableSortedSet<BuildTarget>> providedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> exportedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

  /**
   * A {@link JavaLibrary} registers the ability to create {@link JavaLibrary#SRC_JAR}s when source
   * is present and also {@link JavaLibrary#GWT_MODULE_FLAVOR}, if appropriate.
   */
  @Override
  public void registerFlavors(
      Arg arg,
      BuildRule buildRule,
      ProjectFilesystem projectFilesystem,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      TargetGraph targetGraph,
      BuildRuleResolver ruleResolver) {
  }
}
