package com.facebook.buck.go;

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxConstructorArg;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.HeaderSymlinkTree;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class CGoLibraryDescription implements Description<CGoLibraryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("cgo_library");

  private final GoBuckConfig goBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;

  public CGoLibraryDescription(
      GoBuckConfig goBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms, CxxPlatform defaultCxxPlatform) {
    this.goBuckConfig = goBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
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
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    Path packageName = args.packageName
        .transform(MorePaths.TO_PATH)
        .or(goBuckConfig.getDefaultPackageName(params.getBuildTarget()));

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    CxxPlatform cxxPlatform = cxxPlatforms.getValue(
        params.getBuildTarget()).or(defaultCxxPlatform);

    // Extract all C/C++ sources from the constructor arg.
    ImmutableSortedSet.Builder<SourceWithFlags> cxxSourcesBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableList.Builder<SourcePath> goSourcesBuilder = ImmutableList.builder();
    for (SourceWithFlags src : args.srcs.get()) {
      if (pathResolver.getSourcePathName(params.getBuildTarget(), src.getSourcePath()).endsWith(".go")) {
        if (!src.getFlags().isEmpty()) {
          throw new HumanReadableException(
              "Per-file flags on cgo .go sources are not supported (in rule %s).",
              params.getBuildTarget());
        }
        goSourcesBuilder.add(src.getSourcePath());
      } else {
        cxxSourcesBuilder.add(src);
      }
    }

    ImmutableList<SourcePath> goSources = goSourcesBuilder.build();

    ImmutableMap<String, CxxSource> cxxSrcs =
        CxxDescriptionEnhancer.parseCxxSources(
            params.getBuildTarget(),
            pathResolver,
            cxxPlatform,
            cxxSourcesBuilder.build(),
            args.platformSrcs.get());
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            pathResolver,
            Optional.of(cxxPlatform),
            args);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    HeaderSymlinkTree headerSymlinkTree = CxxDescriptionEnhancer.requireHeaderSymlinkTree(
        params,
        resolver,
        pathResolver,
        cxxPlatform,
        headers,
        HeaderVisibility.PRIVATE);

    ImmutableList<CxxPreprocessorInput> preprocessorInputs = CxxDescriptionEnhancer.collectCxxPreprocessorInput(
        params,
        cxxPlatform,
        CxxFlags.getLanguageFlags(
            args.preprocessorFlags,
            args.platformPreprocessorFlags,
            args.langPreprocessorFlags,
            cxxPlatform),
        ImmutableList.of(headerSymlinkTree),
        args.frameworks.or(ImmutableSortedSet.<FrameworkPath>of()),
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            cxxPlatform,
            FluentIterable.from(params.getDeps())
                .filter(Predicates.instanceOf(CxxPreprocessorDep.class))));

    CxxSourceRuleFactory ruleFactory = new CxxSourceRuleFactory(
        params,
        resolver,
        pathResolver,
        cxxPlatform,
        preprocessorInputs,
        CxxFlags.getFlags(
            args.compilerFlags,
            args.platformCompilerFlags,
            cxxPlatform),
        args.prefixHeader);
    ruleFactory.createCgoBuildRule(resolver, goBuckConfig.getCGoTool().get(), packageName, goSources);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<String> packageName;
    public Optional<List<String>> goCompilerFlags;
    public Optional<List<String>> goLinkerFlags;
  }
}
