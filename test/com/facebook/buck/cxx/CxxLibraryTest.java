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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.python.ImmutablePythonPackageComponents;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxLibraryTest {

  @Test
  public void cxxLibraryInterfaces() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    CxxPlatform cxxPlatform = DefaultCxxPlatforms.build(new FakeBuckConfig());

    // Setup some dummy values for the header info.
    final BuildTarget publicHeaderTarget = BuildTargetFactory.newInstance("//:header");
    final BuildTarget publicHeaderSymlinkTreeTarget = BuildTargetFactory.newInstance("//:symlink");
    final Path publicHeaderSymlinkTreeRoot = Paths.get("symlink/tree/root");
    final BuildTarget privateHeaderTarget = BuildTargetFactory.newInstance("//:privateheader");
    final BuildTarget privateHeaderSymlinkTreeTarget = BuildTargetFactory.newInstance(
        "//:privatesymlink");
    final Path privateHeaderSymlinkTreeRoot = Paths.get("private/symlink/tree/root");

    // Setup some dummy values for the library archive info.
    final BuildRule archive = new FakeBuildRule("//:archive", pathResolver);
    final Path archiveOutput = Paths.get("output/path/lib.a");

    // Setup some dummy values for the library archive info.
    final BuildRule sharedLibrary = new FakeBuildRule("//:shared", pathResolver);
    final Path sharedLibraryOutput = Paths.get("output/path/lib.so");
    final String sharedLibrarySoname = "lib.so";

    // Construct a CxxLibrary object to test.
    FakeCxxLibrary cxxLibrary = new FakeCxxLibrary(
        params,
        pathResolver,
        publicHeaderTarget,
        publicHeaderSymlinkTreeTarget,
        publicHeaderSymlinkTreeRoot,
        privateHeaderTarget,
        privateHeaderSymlinkTreeTarget,
        privateHeaderSymlinkTreeRoot,
        archive,
        archiveOutput,
        sharedLibrary,
        sharedLibraryOutput,
        sharedLibrarySoname,
        ImmutableSortedSet.<BuildTarget>of());

    // Verify that we get the header/symlink targets and root via the CxxPreprocessorDep
    // interface.
    CxxPreprocessorInput expectedPublicCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addRules(publicHeaderTarget, publicHeaderSymlinkTreeTarget)
        .addIncludeRoots(publicHeaderSymlinkTreeRoot)
        .build();
    assertEquals(
        expectedPublicCxxPreprocessorInput,
        cxxLibrary.getCxxPreprocessorInput(
            cxxPlatform,
            CxxDescriptionEnhancer.HeaderVisibility.PUBLIC));

    CxxPreprocessorInput expectedPrivateCxxPreprocessorInput = CxxPreprocessorInput.builder()
        .addRules(privateHeaderTarget, privateHeaderSymlinkTreeTarget)
        .addIncludeRoots(privateHeaderSymlinkTreeRoot)
        .build();
    assertEquals(
        expectedPrivateCxxPreprocessorInput,
        cxxLibrary.getCxxPreprocessorInput(
            cxxPlatform,
            CxxDescriptionEnhancer.HeaderVisibility.PRIVATE));

    // Verify that we get the static archive and it's build target via the NativeLinkable
    // interface.
    NativeLinkableInput expectedStaticNativeLinkableInput = ImmutableNativeLinkableInput.of(
        ImmutableList.<SourcePath>of(
            new BuildTargetSourcePath(projectFilesystem, archive.getBuildTarget())),
        ImmutableList.of(archiveOutput.toString()));
    assertEquals(
        expectedStaticNativeLinkableInput,
        cxxLibrary.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.STATIC));

    // Verify that we get the static archive and it's build target via the NativeLinkable
    // interface.
    NativeLinkableInput expectedSharedNativeLinkableInput = ImmutableNativeLinkableInput.of(
        ImmutableList.<SourcePath>of(
            new BuildTargetSourcePath(projectFilesystem, sharedLibrary.getBuildTarget())),
        ImmutableList.of(sharedLibraryOutput.toString()));
    assertEquals(
        expectedSharedNativeLinkableInput,
        cxxLibrary.getNativeLinkableInput(
            cxxPlatform,
            Linker.LinkableDepType.SHARED));

    // Verify that we return the expected output for python packages.
    PythonPackageComponents expectedPythonPackageComponents = ImmutablePythonPackageComponents.of(
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(),
        ImmutableMap.<Path, SourcePath>of(
            Paths.get(sharedLibrarySoname),
            new PathSourcePath(projectFilesystem, sharedLibraryOutput)),
        ImmutableSet.<SourcePath>of(),
        Optional.<Boolean>absent());
    assertEquals(
        expectedPythonPackageComponents,
        cxxLibrary.getPythonPackageComponents(cxxPlatform));

    // Verify that the implemented BuildRule methods are effectively unused.
    assertEquals(ImmutableList.<Step>of(), cxxLibrary.getBuildSteps(null, null));
    assertNull(cxxLibrary.getPathToOutputFile());
    assertTrue(ImmutableList.copyOf(cxxLibrary.getInputs()).isEmpty());
  }
}
