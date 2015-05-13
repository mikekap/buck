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

package com.facebook.buck.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.DefaultCxxPlatforms;
import com.facebook.buck.io.MorePathsForTests;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonTestDescriptionTest {

  private static final Path PEX_PATH = Paths.get("pex");
  private static final Path PEX_EXECUTER_PATH = MorePathsForTests.rootRelativePath("/not/python2");
  private static final Optional<Path> TEST_MAIN = Optional.of(Paths.get("main"));
  private static final ProjectFilesystem PROJECT_FILESYSTEM = new FakeProjectFilesystem();
  private static final CxxPlatform CXX_PLATFORM = DefaultCxxPlatforms.build(
      new CxxBuckConfig(new FakeBuckConfig()));
  private static final FlavorDomain<CxxPlatform> CXX_PLATFORMS =
      new FlavorDomain<>("platform", ImmutableMap.<Flavor, CxxPlatform>of());

  @Test
  public void thatTestModulesAreInComponents() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:bin"))
            .build();
    PythonTestDescription desc = new PythonTestDescription(
        PROJECT_FILESYSTEM,
        PEX_PATH,
        PEX_EXECUTER_PATH,
        TEST_MAIN,
        new PythonEnvironment(Paths.get("fake_python"), PythonVersion.of("Python 2.7")),
        CXX_PLATFORM,
        CXX_PLATFORMS);
    PythonTestDescription.Arg arg = desc.createUnpopulatedConstructorArg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.srcs = Optional.of(
        Either.<ImmutableSortedSet<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
            ImmutableSortedSet.<SourcePath>of(new TestSourcePath("blah.py"))));
    arg.resources = Optional.absent();
    arg.baseModule = Optional.absent();
    arg.contacts = Optional.absent();
    arg.labels = Optional.absent();
    arg.sourceUnderTest = Optional.absent();
    arg.zipSafe = Optional.absent();
    PythonTest testRule = desc.createBuildRule(params, resolver, arg);

    PythonBinary binRule = (PythonBinary) resolver.getRule(
        desc.getBinaryBuildTarget(testRule.getBuildTarget()));
    assertNotNull(binRule);

    PythonPackageComponents components = binRule.getComponents();
    assertTrue(components.getModules().containsKey(desc.getTestModulesListName()));
    assertTrue(components.getModules().containsKey(desc.getTestMainName()));
    assertEquals(
        binRule.getMainModule(),
        PythonUtil.toModuleName(
            params.getBuildTarget(),
            desc.getTestMainName().toString()));
  }

  @Test
  public void baseModule() {
    BuildRuleResolver resolver;
    BuildTarget target = BuildTargetFactory.newInstance("//foo:lib");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    String sourceName = "main.py";
    SourcePath source = new TestSourcePath("foo/" + sourceName);
    PythonTestDescription desc = new PythonTestDescription(
        PROJECT_FILESYSTEM,
        PEX_PATH,
        PEX_EXECUTER_PATH,
        TEST_MAIN,
        new PythonEnvironment(Paths.get("python"), PythonVersion.of("2.5")),
        CXX_PLATFORM,
        CXX_PLATFORMS);
    PythonTestDescription.Arg arg = desc.createUnpopulatedConstructorArg();
    arg.deps = Optional.absent();
    arg.resources = Optional.absent();
    arg.contacts = Optional.absent();
    arg.labels = Optional.absent();
    arg.sourceUnderTest = Optional.absent();
    arg.srcs = Optional.of(
        Either.<ImmutableSortedSet<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
            ImmutableSortedSet.of(source)));
    arg.zipSafe = Optional.absent();

    // Run without a base module set and verify it defaults to using the build target
    // base name.
    arg.baseModule = Optional.absent();
    resolver = new BuildRuleResolver();
    desc.createBuildRule(params, resolver, arg);
    PythonBinary normalRule = (PythonBinary) resolver.getRule(
        desc.getBinaryBuildTarget(target));
    assertNotNull(normalRule);
    assertTrue(normalRule.getComponents().getModules().containsKey(
        target.getBasePath().resolve(sourceName)));

    // Run *with* a base module set and verify it gets used to build the main module path.
    arg.baseModule = Optional.of("blah");
    resolver = new BuildRuleResolver();
    desc.createBuildRule(params, resolver, arg);
    PythonBinary baseModuleRule = (PythonBinary) resolver.getRule(
        desc.getBinaryBuildTarget(target));
    assertNotNull(baseModuleRule);
    assertTrue(baseModuleRule.getComponents().getModules().containsKey(
        Paths.get(arg.baseModule.get()).resolve(sourceName)));
  }

}
