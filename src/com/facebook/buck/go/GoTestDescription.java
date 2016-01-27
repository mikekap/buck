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

package com.facebook.buck.go;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.annotation.Nullable;
import javax.xml.transform.Source;

public class GoTestDescription implements Description<GoTestDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("go_test");

  private final GoBuckConfig goBuckConfig;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;

  public GoTestDescription(
      GoBuckConfig goBuckConfig,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform) {
    this.goBuckConfig = goBuckConfig;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  private GoTestMain requireTestMainGenRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ImmutableSet<SourcePath> srcs,
      Path packageName) {

    Tool testMainGenerator = GoDescriptors.getTestMainGenerator(
        goBuckConfig,
        params,
        resolver,
        cxxPlatform,
        params.getProjectFilesystem());

    SourcePathResolver sourceResolver = new SourcePathResolver(resolver);
    GoTestMain generatedTestMain = new GoTestMain(
        params.copyWithChanges(
            BuildTarget.builder(params.getBuildTarget())
                .addFlavors(ImmutableFlavor.of("test-main-src"))
                .build(),
            Suppliers.ofInstance(ImmutableSortedSet.copyOf(
                testMainGenerator.getDeps(sourceResolver))),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())
        ),
        sourceResolver,
        testMainGenerator,
        srcs,
        packageName
    );
    resolver.addToIndex(generatedTestMain);
    return generatedTestMain;
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    Optional<GoLibrary> library = Optional.absent();
    if (args.library.isPresent()) {
      BuildRule untypedLibrary = resolver.requireRule(args.library.get());
      if (!(untypedLibrary instanceof GoLibrary)) {
        throw new HumanReadableException(
            "Library specified in %s (%s) is not a go_library rule.",
            params.getBuildTarget(), args.library.get());
      }
      library = Optional.of((GoLibrary) untypedLibrary);
    }

    BuildTarget testLibraryTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("test-library"))
            .build();

    Path packageName;
    if (library.isPresent()) {
      if (args.packageName.isPresent()) {
        throw new HumanReadableException(
            "%s specifies both library and package_name. " +
                "Setting library inherits the package name, so you cannot override it.",
            params.getBuildTarget());
      }
      packageName = library.get().getGoPackageName();
    } else if (args.packageName.isPresent()) {
      packageName = Paths.get(args.packageName.get());
    } else {
      packageName = goBuckConfig.getDefaultPackageName(params.getBuildTarget());
      packageName = packageName.resolveSibling(packageName.getFileName() + "_test");
    }

    ImmutableList.Builder<SourcePath> srcs = ImmutableList.builder();
    srcs.addAll(args.srcs);
    if (library.isPresent()) {
      srcs.addAll(library)
    }

    GoLibrary testLibrary = GoDescriptors.createGoLibraryRule(
        params.copyWithBuildTarget(testLibraryTarget),
        resolver,
        goBuckConfig,
        packageName,
        args.srcs,
        args.compilerFlags.or(ImmutableList.<String>of()),
        ImmutableSortedSet.<BuildTarget>of()

    );
    resolver.addToIndex(testLibrary);

    GoTestMain generatedTestMain = requireTestMainGenRule(
        params, resolver, args.srcs, testLibrary.getGoPackageName());
    GoBinary testMain = GoDescriptors.createGoBinaryRule(
        params.copyWithChanges(
            BuildTarget.builder(params.getBuildTarget())
                .addFlavors(ImmutableFlavor.of("test-main"))
                .build(),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(testLibrary)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(generatedTestMain))
        ),
        resolver,
        goBuckConfig,
        cxxPlatform,
        ImmutableSet.<SourcePath>of(new BuildTargetSourcePath(generatedTestMain.getBuildTarget())),
        args.compilerFlags.or(ImmutableList.<String>of()),
        args.linkerFlags.or(ImmutableList.<String>of()));
    resolver.addToIndex(testMain);

    return new GoTest(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(testMain)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())
        ),
        new SourcePathResolver(resolver),
        testMain,
        args.labels.get(),
        args.contacts.get(),
        args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
        args.runTestSeparately.or(false));
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public ImmutableSet<SourcePath> srcs;
    @Hint(isDep = false)
    public Optional<BuildTarget> library;
    public Optional<String> packageName;
    public Optional<List<String>> compilerFlags;
    public Optional<List<String>> linkerFlags;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<ImmutableSet<String>> contacts;
    public Optional<ImmutableSet<Label>> labels;
    public Optional<Long> testRuleTimeoutMs;
    public Optional<Boolean> runTestSeparately;
  }
}
