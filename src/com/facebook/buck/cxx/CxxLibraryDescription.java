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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

@Value.Nested
public class CxxLibraryDescription implements
    Description<CxxLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<CxxLibraryDescription.Arg>,
    Flavored {
  public static enum Type {
    HEADERS,
    EXPORTED_HEADERS,
    HEADER_MAP_FILE,
    EXPORTED_HEADER_MAP_FILE,
    SHARED,
    STATIC,
    STATIC_PIC
  }

  public static final BuildRuleType TYPE = BuildRuleType.of("cxx_library");

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      new FlavorDomain<>(
          "C/C++ Library Type",
          ImmutableMap.<Flavor, Type>builder()
              .put(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR, Type.HEADERS)
              .put(
                  CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
                  Type.EXPORTED_HEADERS)
              .put(CxxDescriptionEnhancer.HEADER_MAP_FILE_FLAVOR, Type.HEADER_MAP_FILE)
              .put(
                  CxxDescriptionEnhancer.EXPORTED_HEADER_MAP_FILE_FLAVOR,
                  Type.EXPORTED_HEADER_MAP_FILE)
              .put(CxxDescriptionEnhancer.SHARED_FLAVOR, Type.SHARED)
              .put(CxxDescriptionEnhancer.STATIC_FLAVOR, Type.STATIC)
              .put(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR, Type.STATIC_PIC)
              .build());

  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxSourceRuleFactory.Strategy compileStrategy;

  public CxxLibraryDescription(
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxSourceRuleFactory.Strategy compileStrategy) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
    this.compileStrategy = compileStrategy;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatforms.containsAnyOf(flavors);
  }

  private static ImmutableList<SourcePath> requireObjects(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<SourcePath> prefixHeaders,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableList<Path> frameworkSearchPaths,
      CxxSourceRuleFactory.Strategy compileStrategy,
      CxxSourceRuleFactory.PicType pic) {

    CxxHeaderSourceSpec lexYaccSources =
        CxxDescriptionEnhancer.requireLexYaccSources(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            lexSources,
            yaccSources);

    SymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            /* includeLexYaccHeaders */ true,
            lexSources,
            yaccSources,
            headers,
            CxxDescriptionEnhancer.HeaderVisibility.PRIVATE);

    SymlinkTree exportedHeaderSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            /* includeLexYaccHeaders */ false,
            ImmutableMap.<String, SourcePath>of(),
            ImmutableMap.<String, SourcePath>of(),
            exportedHeaders,
            CxxDescriptionEnhancer.HeaderVisibility.PUBLIC);

    CxxPreprocessorInput cxxPreprocessorInputFromDependencies =
        CxxDescriptionEnhancer.combineCxxPreprocessorInput(
            params,
            cxxPlatform,
            preprocessorFlags,
            prefixHeaders,
            ImmutableList.of(
                headerSymlinkTree,
                exportedHeaderSymlinkTree),
            frameworkSearchPaths);

    ImmutableMap<String, CxxSource> allSources =
        ImmutableMap.<String, CxxSource>builder()
            .putAll(sources)
            .putAll(lexYaccSources.getCxxSources())
            .build();

    // Create rule to build the object files.
    return CxxSourceRuleFactory.createPreprocessAndCompileRules(
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        cxxPreprocessorInputFromDependencies,
        compilerFlags,
        compileStrategy,
        allSources,
        pic);
  }

  /**
   * Create all build rules needed to generate the static library.
   *
   * @return the {@link Archive} rule representing the actual static library.
   */
  private static Archive createStaticLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      CxxSourceRuleFactory.PicType picType,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<SourcePath> prefixHeaders,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableList<Path> frameworkSearchPaths,
      CxxSourceRuleFactory.Strategy compileStrategy) {

    // Create rules for compiling the object files.
    ImmutableList<SourcePath> objects = requireObjects(
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        lexSources,
        yaccSources,
        preprocessorFlags,
        prefixHeaders,
        headers,
        exportedHeaders,
        compilerFlags,
        sources,
        frameworkSearchPaths,
        compileStrategy,
        picType);

    // Write a build rule to create the archive for this C/C++ library.
    BuildTarget staticTarget;
    Path staticLibraryPath;
    if (picType == CxxSourceRuleFactory.PicType.PIC) {
      staticTarget = CxxDescriptionEnhancer.createStaticPicLibraryBuildTarget(
          params.getBuildTarget(),
          cxxPlatform.getFlavor());

      staticLibraryPath = CxxDescriptionEnhancer.getStaticPicLibraryPath(
          params.getBuildTarget(),
          cxxPlatform.getFlavor());
    } else {
      staticTarget = CxxDescriptionEnhancer.createStaticLibraryBuildTarget(
          params.getBuildTarget(),
          cxxPlatform.getFlavor());

      staticLibraryPath = CxxDescriptionEnhancer.getStaticLibraryPath(
          params.getBuildTarget(),
          cxxPlatform.getFlavor());
    }
    Archive staticLibraryBuildRule = Archives.createArchiveRule(
        pathResolver,
        staticTarget,
        params,
        cxxPlatform.getAr(),
        staticLibraryPath,
        objects);

    return staticLibraryBuildRule;
  }

  /**
   * Create all build rules needed to generate the shared library.
   *
   * @return the {@link CxxLink} rule representing the actual shared library.
   */
  private static CxxLink createSharedLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, SourcePath> lexSources,
      ImmutableMap<String, SourcePath> yaccSources,
      ImmutableMultimap<CxxSource.Type, String> preprocessorFlags,
      ImmutableList<SourcePath> prefixHeaders,
      ImmutableMap<Path, SourcePath> headers,
      ImmutableMap<Path, SourcePath> exportedHeaders,
      ImmutableList<String> compilerFlags,
      ImmutableMap<String, CxxSource> sources,
      ImmutableList<String> linkerFlags,
      ImmutableList<Path> frameworkSearchPaths,
      Optional<String> soname,
      CxxSourceRuleFactory.Strategy compileStrategy) {

    // Create rules for compiling the PIC object files.
    ImmutableList<SourcePath> objects = requireObjects(
        params,
        ruleResolver,
        pathResolver,
        cxxPlatform,
        lexSources,
        yaccSources,
        preprocessorFlags,
        prefixHeaders,
        headers,
        exportedHeaders,
        compilerFlags,
        sources,
        frameworkSearchPaths,
        compileStrategy,
        CxxSourceRuleFactory.PicType.PIC);

    // Setup the rules to link the shared library.
    BuildTarget sharedTarget =
        CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
            params.getBuildTarget(),
            cxxPlatform.getFlavor());
    String sharedLibrarySoname =
        soname.or(
            CxxDescriptionEnhancer.getSharedLibrarySoname(params.getBuildTarget(), cxxPlatform));
    Path sharedLibraryPath = CxxDescriptionEnhancer.getSharedLibraryPath(
        params.getBuildTarget(),
        cxxPlatform);
    ImmutableList.Builder<String> extraCxxLdFlagsBuilder = ImmutableList.builder();
    extraCxxLdFlagsBuilder.addAll(
        MoreIterables.zipAndConcat(
            Iterables.cycle("-F"),
            Iterables.transform(frameworkSearchPaths, Functions.toStringFunction())));
    ImmutableList<String> extraCxxLdFlags = extraCxxLdFlagsBuilder.build();

    CxxLink sharedLibraryBuildRule =
        CxxLinkableEnhancer.createCxxLinkableBuildRule(
            cxxPlatform,
            params,
            pathResolver,
            extraCxxLdFlags,
            /* extraLdFlags */ linkerFlags,
            sharedTarget,
            Linker.LinkType.SHARED,
            Optional.of(sharedLibrarySoname),
            sharedLibraryPath,
            objects,
            Linker.LinkableDepType.SHARED,
            params.getDeps());

    return sharedLibraryBuildRule;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  public static Arg createEmptyConstructorArg() {
    Arg arg = new Arg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    arg.srcs = Optional.of(
        Either.<ImmutableList<SourceWithFlags>, ImmutableMap<String, SourceWithFlags>>ofLeft(
            ImmutableList.<SourceWithFlags>of()));
    arg.prefixHeaders = Optional.of(ImmutableList.<SourcePath>of());
    arg.headers = Optional.of(
        Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
            ImmutableList.<SourcePath>of()));
    arg.exportedHeaders = Optional.of(
        Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
            ImmutableList.<SourcePath>of()));
    arg.compilerFlags = Optional.of(ImmutableList.<String>of());
    arg.platformCompilerFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    arg.exportedPreprocessorFlags = Optional.of(ImmutableList.<String>of());
    arg.exportedPlatformPreprocessorFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    arg.exportedLangPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    arg.preprocessorFlags = Optional.of(ImmutableList.<String>of());
    arg.platformPreprocessorFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    arg.langPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    arg.linkerFlags = Optional.of(ImmutableList.<String>of());
    arg.platformLinkerFlags = Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    arg.linkWhole = Optional.absent();
    arg.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    arg.yaccSrcs = Optional.of(ImmutableList.<SourcePath>of());
    arg.headerNamespace = Optional.absent();
    arg.soname = Optional.absent();
    arg.frameworkSearchPaths = Optional.of(ImmutableList.<Path>of());
    arg.tests = Optional.of(ImmutableSortedSet.<BuildTarget>of());
    return arg;
  }

  /**
   * @return a {@link SymlinkTree} for the headers of this C/C++ library.
   */
  public static <A extends Arg> SymlinkTree createHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args) {
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        /* includeLexYaccHeaders */ true,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args),
        CxxDescriptionEnhancer.HeaderVisibility.PRIVATE);
  }

  /**
   * @return a {@link SymlinkTree} for the exported headers of this C/C++ library.
   */
  public static <A extends Arg> SymlinkTree createExportedHeaderSymlinkTreeBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args) {
    return CxxDescriptionEnhancer.createHeaderSymlinkTree(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        /* includeLexYaccHeaders */ false,
        ImmutableMap.<String, SourcePath>of(),
        ImmutableMap.<String, SourcePath>of(),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, args),
        CxxDescriptionEnhancer.HeaderVisibility.PUBLIC);
  }

  /**
   * @return a {@link HeaderMapFile} for the headers of this C/C++ library.
   */
  public static <A extends Arg> HeaderMapFile createHeaderMapFileBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args) {
    return CxxDescriptionEnhancer.createHeaderMapFile(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        /* includeLexYaccHeaders */ true,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args),
        CxxDescriptionEnhancer.HeaderVisibility.PRIVATE);
  }

  /**
   * @return a {@link HeaderMapFile} for the exported headers of this C/C++ library.
   */
  public static <A extends Arg> HeaderMapFile createExportedHeaderMapFileBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args) {
    return CxxDescriptionEnhancer.createHeaderMapFile(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        /* includeLexYaccHeaders */ false,
        ImmutableMap.<String, SourcePath>of(),
        ImmutableMap.<String, SourcePath>of(),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, args),
        CxxDescriptionEnhancer.HeaderVisibility.PUBLIC);
  }

  /**
   * @return a {@link Archive} rule which builds a static library version of this C/C++ library.
   */
  public static <A extends Arg> Archive createStaticLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args,
      CxxSourceRuleFactory.Strategy compileStrategy) {
    return createStaticLibrary(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxSourceRuleFactory.PicType.PDC,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        ImmutableMultimap.<CxxSource.Type, String>builder()
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.preprocessorFlags,
                    args.platformPreprocessorFlags,
                    args.langPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.exportedPreprocessorFlags,
                    args.exportedPlatformPreprocessorFlags,
                    args.exportedLangPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .build(),
        args.prefixHeaders.get(),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, args),
        CxxFlags.getFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            cxxPlatform.getFlavor()),
        CxxDescriptionEnhancer.parseCxxSources(params, resolver, args),
        args.frameworkSearchPaths.get(),
        compileStrategy);
  }

  /**
   * @return a {@link Archive} rule which builds a position-independent static library version
   * of this C/C++ library.
   */
  public static <A extends Arg> Archive createStaticPicLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args,
      CxxSourceRuleFactory.Strategy compileStrategy) {
    return createStaticLibrary(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxSourceRuleFactory.PicType.PIC,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        ImmutableMultimap.<CxxSource.Type, String>builder()
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.preprocessorFlags,
                    args.platformPreprocessorFlags,
                    args.langPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.exportedPreprocessorFlags,
                    args.exportedPlatformPreprocessorFlags,
                    args.exportedLangPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .build(),
        args.prefixHeaders.get(),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, args),
        CxxFlags.getFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            cxxPlatform.getFlavor()),
        CxxDescriptionEnhancer.parseCxxSources(params, resolver, args),
        args.frameworkSearchPaths.get(),
        compileStrategy);
  }

  /**
   * @return a {@link CxxLink} rule which builds a shared library version of this C/C++ library.
   */
  public static <A extends Arg> CxxLink createSharedLibraryBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      A args,
      CxxSourceRuleFactory.Strategy compileStrategy) {
    return createSharedLibrary(
        params,
        resolver,
        new SourcePathResolver(resolver),
        cxxPlatform,
        CxxDescriptionEnhancer.parseLexSources(params, resolver, args),
        CxxDescriptionEnhancer.parseYaccSources(params, resolver, args),
        ImmutableMultimap.<CxxSource.Type, String>builder()
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.preprocessorFlags,
                    args.platformPreprocessorFlags,
                    args.langPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .putAll(
                CxxFlags.getLanguageFlags(
                    args.exportedPreprocessorFlags,
                    args.exportedPlatformPreprocessorFlags,
                    args.exportedLangPreprocessorFlags,
                    cxxPlatform.getFlavor()))
            .build(),
        args.prefixHeaders.get(),
        CxxDescriptionEnhancer.parseHeaders(params, resolver, args),
        CxxDescriptionEnhancer.parseExportedHeaders(params, resolver, args),
        CxxFlags.getFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            cxxPlatform.getFlavor()),
        CxxDescriptionEnhancer.parseCxxSources(params, resolver, args),
        CxxFlags.getFlags(
            args.linkerFlags,
            args.platformLinkerFlags,
            cxxPlatform.getFlavor()),
        args.frameworkSearchPaths.get(),
        args.soname,
        compileStrategy);
  }

  @Value.Immutable
  @BuckStyleImmutable
  public static interface TypeAndPlatform {
    @Value.Parameter
    Optional<Map.Entry<Flavor, Type>> getType();

    @Value.Parameter
    Optional<Map.Entry<Flavor, CxxPlatform>> getPlatform();
  }

  public static TypeAndPlatform getTypeAndPlatform(
      BuildTarget buildTarget,
      FlavorDomain<CxxPlatform> platforms) {
    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, Type>> type;
    Optional<Map.Entry<Flavor, CxxPlatform>> platform;
    try {
      type = LIBRARY_TYPE.getFlavorAndValue(
          ImmutableSet.copyOf(buildTarget.getFlavors()));
      platform = platforms.getFlavorAndValue(
          ImmutableSet.copyOf(buildTarget.getFlavors()));
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", buildTarget, e.getMessage());
    }
    return ImmutableCxxLibraryDescription.TypeAndPlatform.of(type, platform);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    TypeAndPlatform typeAndPlatform = getTypeAndPlatform(
        params.getBuildTarget(),
        cxxPlatforms);
    return createBuildRule(params, resolver, args, typeAndPlatform);
  }

  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      final A args,
      TypeAndPlatform typeAndPlatform) {
    Optional<Map.Entry<Flavor, Type>> type = typeAndPlatform.getType();
    Optional<Map.Entry<Flavor, CxxPlatform>> platform = typeAndPlatform.getPlatform();

    // If we *are* building a specific type of this lib, call into the type specific
    // rule builder methods.
    if (type.isPresent() && platform.isPresent()) {
      Set<Flavor> flavors = Sets.newHashSet(params.getBuildTarget().getFlavors());
      flavors.remove(type.get().getKey());
      BuildTarget target = BuildTarget
          .builder(params.getBuildTarget().getUnflavoredBuildTarget())
          .addAllFlavors(flavors)
          .build();
      BuildRuleParams typeParams =
          params.copyWithChanges(
              params.getBuildRuleType(),
              target,
              Suppliers.ofInstance(params.getDeclaredDeps()),
              Suppliers.ofInstance(params.getExtraDeps()));
      if (type.get().getValue().equals(Type.HEADERS)) {
        return createHeaderSymlinkTreeBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args);
      } else if (type.get().getValue().equals(Type.EXPORTED_HEADERS)) {
        return createExportedHeaderSymlinkTreeBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args);
      } else if (type.get().getValue().equals(Type.HEADER_MAP_FILE)) {
        return createHeaderMapFileBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args);
      } else if (type.get().getValue().equals(Type.EXPORTED_HEADER_MAP_FILE)) {
        return createExportedHeaderMapFileBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args);
      } else if (type.get().getValue().equals(Type.SHARED)) {
        return createSharedLibraryBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args,
            compileStrategy);
      } else if (type.get().getValue().equals(Type.STATIC_PIC)) {
        return createStaticPicLibraryBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args,
            compileStrategy);
      } else {
        return createStaticLibraryBuildRule(
            typeParams,
            resolver,
            platform.get().getValue(),
            args,
            compileStrategy);
      }
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return new CxxLibrary(
        params,
        resolver,
        pathResolver,
        new Function<CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>() {
          @Override
          public ImmutableMultimap<CxxSource.Type, String> apply(CxxPlatform input) {
            return CxxFlags.getLanguageFlags(
                args.exportedPreprocessorFlags,
                args.exportedPlatformPreprocessorFlags,
                args.exportedLangPreprocessorFlags,
                input.getFlavor());
          }
        },
        new Function<CxxPlatform, ImmutableList<String>>() {
          @Override
          public ImmutableList<String> apply(CxxPlatform input) {
            return CxxFlags.getFlags(
                args.linkerFlags,
                args.platformLinkerFlags,
                input.getFlavor());
          }
        },
        args.frameworkSearchPaths.get(),
        args.linkWhole.or(false),
        args.soname,
        args.tests.get());
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    if (constructorArg.lexSrcs.isPresent() && !constructorArg.lexSrcs.get().isEmpty()) {
      deps.add(cxxBuckConfig.getLexDep());
    }

    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<Either<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>>
        exportedHeaders;
    public Optional<ImmutableList<String>> exportedPreprocessorFlags;
    public Optional<ImmutableList<Pair<String, ImmutableList<String>>>>
        exportedPlatformPreprocessorFlags;
    public Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>>
        exportedLangPreprocessorFlags;
    public Optional<String> soname;
    public Optional<Boolean> linkWhole;
  }

}
