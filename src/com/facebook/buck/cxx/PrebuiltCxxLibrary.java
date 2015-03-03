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

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.model.Pair;
import com.facebook.buck.python.ImmutablePythonPackageComponents;
import com.facebook.buck.python.PythonPackageComponents;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PrebuiltCxxLibrary extends AbstractCxxLibrary {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final SourcePathResolver pathResolver;
  private final ImmutableList<Path> includeDirs;
  private final Optional<String> libDir;
  private final Optional<String> libName;
  private final ImmutableList<String> linkerFlags;
  private final ImmutableList<Pair<String, ImmutableList<String>>> platformLinkerFlags;
  private final Optional<String> soname;
  private final boolean headerOnly;
  private final boolean linkWhole;
  private final boolean provided;

  public PrebuiltCxxLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      ImmutableList<Path> includeDirs,
      Optional<String> libDir,
      Optional<String> libName,
      ImmutableList<String> linkerFlags,
      ImmutableList<Pair<String, ImmutableList<String>>> platformLinkerFlags,
      Optional<String> soname,
      boolean headerOnly,
      boolean linkWhole,
      boolean provided) {
    super(params, pathResolver);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.pathResolver = pathResolver;
    this.includeDirs = includeDirs;
    this.libDir = libDir;
    this.libName = libName;
    this.linkerFlags = linkerFlags;
    this.platformLinkerFlags = platformLinkerFlags;
    this.soname = soname;
    this.headerOnly = headerOnly;
    this.linkWhole = linkWhole;
    this.provided = provided;
  }

  /**
   * Makes sure all build rules needed to produce the shared library are added to the action
   * graph.
   *
   * @return the {@link SourcePath} representing the actual shared library.
   */
  private SourcePath requireSharedLibrary(CxxPlatform cxxPlatform) {
    Path sharedLibraryPath =
        PrebuiltCxxLibraryDescription.getSharedLibraryPath(
            getBuildTarget(),
            cxxPlatform,
            libDir,
            libName);

    // If the shared library is prebuilt, just return a reference to it.
    if (params.getProjectFilesystem().exists(sharedLibraryPath)) {
      return new PathSourcePath(params.getProjectFilesystem(), sharedLibraryPath);
    }

    // Otherwise, generate it's build rule.
    BuildRule sharedLibrary =
        CxxDescriptionEnhancer.requireBuildRule(
            params,
            ruleResolver,
            cxxPlatform.getFlavor(),
            CxxDescriptionEnhancer.SHARED_FLAVOR);

    return new BuildTargetSourcePath(
        sharedLibrary.getProjectFilesystem(),
        sharedLibrary.getBuildTarget());
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      CxxDescriptionEnhancer.HeaderVisibility headerVisibility) {
    switch (headerVisibility) {
      case PUBLIC:
        return CxxPreprocessorInput.builder()
            // Just pass the include dirs as system includes.
            .addAllSystemIncludeRoots(includeDirs)
            .build();
      case PRIVATE:
        return CxxPreprocessorInput.EMPTY;
    }

    // We explicitly don't put this in a default statement because we
    // want the compiler to warn if someone modifies the HeaderVisibility enum.
    throw new RuntimeException("Invalid header visibility: " + headerVisibility);
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) {

    // Build the library path and linker arguments that we pass through the
    // {@link NativeLinkable} interface for linking.
    ImmutableList.Builder<SourcePath> librariesBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(linkerFlags);
    linkerArgsBuilder.addAll(
        CxxDescriptionEnhancer.getPlatformFlags(
            platformLinkerFlags,
            cxxPlatform.getFlavor().toString()));
    if (!headerOnly) {
      if (provided || type == Linker.LinkableDepType.SHARED) {
        SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform);
        librariesBuilder.add(sharedLibrary);
        linkerArgsBuilder.add(pathResolver.getPath(sharedLibrary).toString());
      } else {
        Path staticLibraryPath =
            PrebuiltCxxLibraryDescription.getStaticLibraryPath(
                getBuildTarget(),
                cxxPlatform,
                libDir,
                libName);
        librariesBuilder.add(new PathSourcePath(getProjectFilesystem(), staticLibraryPath));
        if (linkWhole) {
          Linker linker = cxxPlatform.getLd();
          linkerArgsBuilder.addAll(linker.linkWhole(staticLibraryPath.toString()));
        } else {
          linkerArgsBuilder.add(staticLibraryPath.toString());
        }
      }
    }
    final ImmutableList<SourcePath> libraries = librariesBuilder.build();
    final ImmutableList<String> linkerArgs = linkerArgsBuilder.build();

    return ImmutableNativeLinkableInput.of(/* inputs */ libraries, /* args */ linkerArgs);
  }

  @Override
  public PythonPackageComponents getPythonPackageComponents(CxxPlatform cxxPlatform) {
    String resolvedSoname =
        PrebuiltCxxLibraryDescription.getSoname(getBuildTarget(), cxxPlatform, soname, libName);

    // Build up the shared library list to contribute to a python executable package.
    ImmutableMap.Builder<Path, SourcePath> nativeLibrariesBuilder = ImmutableMap.builder();
    if (!headerOnly && !provided) {
      SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform);
      nativeLibrariesBuilder.put(
          Paths.get(resolvedSoname),
          sharedLibrary);
    }
    ImmutableMap<Path, SourcePath> nativeLibraries = nativeLibrariesBuilder.build();

    return ImmutablePythonPackageComponents.of(
        /* modules */ ImmutableMap.<Path, SourcePath>of(),
        /* resources */ ImmutableMap.<Path, SourcePath>of(),
        nativeLibraries,
        /* requirements */ ImmutableSet.<SourcePath>of(),
        /* zipSafe */ Optional.<Boolean>absent());
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(params.getDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addNativeLinkable(this);
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    String resolvedSoname =
        PrebuiltCxxLibraryDescription.getSoname(getBuildTarget(), cxxPlatform, soname, libName);
    ImmutableMap.Builder<String, SourcePath> solibs = ImmutableMap.builder();
    if (!headerOnly && !provided) {
      SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform);
      solibs.put(resolvedSoname, sharedLibrary);
    }
    return solibs.build();
  }

}
