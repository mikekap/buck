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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.core.SuggestBuildRules;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.reflect.ClassPath;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUILD</code>:
 * <pre>
 * java_library(
 *   name = 'feed',
 *   srcs = [
 *     'FeedStoryRenderer.java',
 *   ],
 *   deps = [
 *     '//src/com/facebook/feed/model:model',
 *     '//third-party/java/guava:guava',
 *   ],
 * )
 * </pre>
 * Then this would compile {@code FeedStoryRenderer.java} against Guava and the classes generated
 * from the {@code //src/com/facebook/feed/model:model} rule.
 */
public class DefaultJavaLibrary extends AbstractBuildRule
    implements JavaLibrary, HasClasspathEntries, ExportDependencies,
    InitializableFromDisk<JavaLibrary.Data>, AndroidPackageable,
    SupportsInputBasedRuleKey, HasTests {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey
  private final ImmutableSortedSet<SourcePath> resources;
  @AddToRuleKey(stringify = true)
  private final Optional<Path> resourcesRoot;
  @AddToRuleKey
  private final Optional<String> mavenCoords;
  private final Optional<Path> outputJar;
  @AddToRuleKey
  private final Optional<SourcePath> proguardConfig;
  @AddToRuleKey
  private final ImmutableList<String> postprocessClassesCommands;
  private final ImmutableSortedSet<BuildRule> exportedDeps;
  private final ImmutableSortedSet<BuildRule> providedDeps;
  private final Supplier<ImmutableSortedSet<BuildRule>> transitiveExportedDeps;
  // Some classes need to override this when enhancing deps (see AndroidLibrary).
  private final ImmutableSet<Path> additionalClasspathEntries;
  private final Supplier<ImmutableSetMultimap<JavaLibrary, Path>>
      transitiveClasspathEntriesSupplier;
  private final Supplier<ImmutableSet<JavaLibrary>> transitiveClasspathDepsSupplier;

  private final SourcePath abiJar;
  @AddToRuleKey
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final Supplier<ImmutableSortedSet<SourcePath>> abiClasspath;

  private final BuildOutputInitializer<Data> buildOutputInitializer;
  private final ImmutableSortedSet<BuildTarget> tests;
  private final Optional<Path> generatedSourceFolder;

  @AddToRuleKey
  private final CompileToJarStepFactory compileStepFactory;

  @Override
  public ImmutableSortedSet<BuildTarget> getTests() {
    return tests;
  }

  private static final SuggestBuildRules.JarResolver JAR_RESOLVER =
      new SuggestBuildRules.JarResolver() {
    @Override
    public ImmutableSet<String> resolve(Path classPath) {
      ImmutableSet.Builder<String> topLevelSymbolsBuilder = ImmutableSet.builder();
      try {
        ClassLoader loader = URLClassLoader.newInstance(
            new URL[]{classPath.toUri().toURL()},
          /* parent */ null);

        // For every class contained in that jar, check to see if the package name
        // (e.g. com.facebook.foo), the simple name (e.g. ImmutableSet) or the name
        // (e.g com.google.common.collect.ImmutableSet) is one of the missing symbols.
        for (ClassPath.ClassInfo classInfo : ClassPath.from(loader).getTopLevelClasses()) {
          topLevelSymbolsBuilder.add(classInfo.getPackageName(),
              classInfo.getSimpleName(),
              classInfo.getName());
        }
      } catch (IOException e) {
        // Since this simply is a heuristic, return an empty set if we fail to load a jar.
        return topLevelSymbolsBuilder.build();
      }
      return topLevelSymbolsBuilder.build();
    }
  };

  public DefaultJavaLibrary(
      final BuildRuleParams params,
      SourcePathResolver resolver,
      Set<? extends SourcePath> srcs,
      Set<? extends SourcePath> resources,
      Optional<Path> generatedSourceFolder,
      Optional<SourcePath> proguardConfig,
      ImmutableList<String> postprocessClassesCommands,
      ImmutableSortedSet<BuildRule> exportedDeps,
      ImmutableSortedSet<BuildRule> providedDeps,
      SourcePath abiJar,
      ImmutableSet<Path> additionalClasspathEntries,
      CompileToJarStepFactory compileStepFactory,
      Optional<Path> resourcesRoot,
      Optional<String> mavenCoords,
      ImmutableSortedSet<BuildTarget> tests) {
    this(new DefaultJavaLibraryParams(
        params,
        resolver,
        srcs,
        resources,
        generatedSourceFolder,
        proguardConfig,
        postprocessClassesCommands,
        exportedDeps,
        providedDeps,
        abiJar,
        additionalClasspathEntries,
        compileStepFactory,
        resourcesRoot,
        mavenCoords,
        tests));
  }

  private static class DefaultJavaLibraryParams {
    final BuildRuleParams params;
    final SourcePathResolver resolver;
    final Set<? extends SourcePath> srcs;
    final Set<? extends SourcePath> resources;
    final Optional<Path> generatedSourceFolder;
    final Optional<SourcePath> proguardConfig;
    final ImmutableList<String> postprocessClassesCommands;
    final ImmutableSortedSet<BuildRule> exportedDeps;
    final ImmutableSortedSet<BuildRule> providedDeps;
    final Supplier<ImmutableSortedSet<BuildRule>> transitiveExportedDeps;
    final SourcePath abiJar;
    final ImmutableSet<Path> additionalClasspathEntries;
    final CompileToJarStepFactory compileStepFactory;
    final Optional<Path> resourcesRoot;
    final Optional<String> mavenCoords;
    final ImmutableSortedSet<BuildTarget> tests;
    final Supplier<ImmutableSortedSet<SourcePath>> abiClasspath;

    public DefaultJavaLibraryParams(
        BuildRuleParams params,
        final SourcePathResolver resolver,
        Set<? extends SourcePath> srcs,
        Set<? extends SourcePath> resources,
        Optional<Path> generatedSourceFolder,
        Optional<SourcePath> proguardConfig,
        ImmutableList<String> postprocessClassesCommands,
        final ImmutableSortedSet<BuildRule> exportedDeps,
        final ImmutableSortedSet<BuildRule> providedDeps,
        SourcePath abiJar,
        ImmutableSet<Path> additionalClasspathEntries,
        CompileToJarStepFactory compileStepFactory,
        Optional<Path> resourcesRoot,
        Optional<String> mavenCoords,
        ImmutableSortedSet<BuildTarget> tests) {
      final Supplier<ImmutableSortedSet<BuildRule>> inputDeclaredDeps = params.getDeclaredDeps();
      final Supplier<ImmutableSortedSet<BuildRule>> inputExtraDeps = params.getExtraDeps();

      this.transitiveExportedDeps = Suppliers.memoize(
          new Supplier<ImmutableSortedSet<BuildRule>>() {
            @Override
            public ImmutableSortedSet<BuildRule> get() {
              return BuildRules.getExportedRules(
                  Iterables.concat(
                      inputDeclaredDeps.get(),
                      exportedDeps,
                      providedDeps));
            }
          });

      params = params.copyWithExtraDeps(new Supplier<ImmutableSortedSet<BuildRule>>() {
        @Override
        public ImmutableSortedSet<BuildRule> get() {
          return ImmutableSortedSet.<BuildRule>naturalOrder()
              .addAll(inputExtraDeps.get())
              .addAll(providedDeps)
              .addAll(exportedDeps)
              .addAll(transitiveExportedDeps.get())
              .build();
        }
      });

      final Supplier<ImmutableSortedSet<BuildRule>> finalDeps = params.getTotalDeps();
      this.abiClasspath = Suppliers.memoize(
          new Supplier<ImmutableSortedSet<SourcePath>>() {
            @Override
            public ImmutableSortedSet<SourcePath> get() {
              return JavaLibraryRules.getAbiInputs(finalDeps.get());
            }
          });

      this.params = params.appendExtraDeps(new Supplier<Iterable<? extends BuildRule>>() {
        @Override
        public Iterable<? extends BuildRule> get() {
          return resolver.filterBuildRuleInputs(abiClasspath.get());
        }
      });
      this.resolver = resolver;
      this.srcs = srcs;
      this.resources = resources;
      this.generatedSourceFolder = generatedSourceFolder;
      this.proguardConfig = proguardConfig;
      this.postprocessClassesCommands = postprocessClassesCommands;
      this.exportedDeps = exportedDeps;
      this.providedDeps = providedDeps;
      this.abiJar = abiJar;
      this.additionalClasspathEntries = additionalClasspathEntries;
      this.compileStepFactory = compileStepFactory;
      this.resourcesRoot = resourcesRoot;
      this.mavenCoords = mavenCoords;
      this.tests = tests;
    }
  }

  private DefaultJavaLibrary(DefaultJavaLibraryParams libraryParams) {
    super(libraryParams.params, libraryParams.resolver);
    this.compileStepFactory = libraryParams.compileStepFactory;

    // Exported deps are meant to be forwarded onto the CLASSPATH for dependents,
    // and so only make sense for java library types.
    for (BuildRule dep : libraryParams.exportedDeps) {
      if (!(dep instanceof JavaLibrary)) {
        throw new HumanReadableException(
            getBuildTarget() + ": exported dep " +
            dep.getBuildTarget() + " (" + dep.getType() + ") " +
            "must be a type of java library.");
      }
    }

    this.srcs = ImmutableSortedSet.copyOf(libraryParams.srcs);
    this.resources = ImmutableSortedSet.copyOf(libraryParams.resources);
    this.proguardConfig = libraryParams.proguardConfig;
    this.postprocessClassesCommands = libraryParams.postprocessClassesCommands;
    this.exportedDeps = libraryParams.exportedDeps;
    this.providedDeps = libraryParams.providedDeps;
    this.transitiveExportedDeps = libraryParams.transitiveExportedDeps;
    this.additionalClasspathEntries = FluentIterable
        .from(libraryParams.additionalClasspathEntries)
        .transform(getProjectFilesystem().getAbsolutifier())
        .toSet();
    this.resourcesRoot = libraryParams.resourcesRoot;
    this.mavenCoords = libraryParams.mavenCoords;
    this.tests = libraryParams.tests;

    this.abiJar = libraryParams.abiJar;
    this.abiClasspath = libraryParams.abiClasspath;
    this.generatedSourceFolder = libraryParams.generatedSourceFolder;

    if (!srcs.isEmpty() || !resources.isEmpty()) {
      this.outputJar = Optional.of(getOutputJarPath(getBuildTarget()));
    } else {
      this.outputJar = Optional.absent();
    }

    this.transitiveClasspathEntriesSupplier =
        Suppliers.memoize(new Supplier<ImmutableSetMultimap<JavaLibrary, Path>>() {
          @Override
          public ImmutableSetMultimap<JavaLibrary, Path> get() {
            return JavaLibraryClasspathProvider.getTransitiveClasspathEntries(
                DefaultJavaLibrary.this,
                getResolver(),
                sourcePathForOutputJar());
          }
        });

    this.transitiveClasspathDepsSupplier =
        Suppliers.memoize(
            new Supplier<ImmutableSet<JavaLibrary>>() {
              @Override
              public ImmutableSet<JavaLibrary> get() {
                return JavaLibraryClasspathProvider.getTransitiveClasspathDeps(
                    DefaultJavaLibrary.this,
                    sourcePathForOutputJar());
              }
            });

    this.buildOutputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);
  }

  private Path getPathToAbiOutputDir() {
    return BuildTargets.getGenPath(getBuildTarget(), "lib__%s__abi");
  }

  private static Path getOutputJarDirPath(BuildTarget target) {
    return BuildTargets.getGenPath(target, "lib__%s__output");
  }

  private Optional<SourcePath> sourcePathForOutputJar() {
    return outputJar.transform(SourcePaths.getToBuildTargetSourcePath(getBuildTarget()));
  }

  static Path getOutputJarPath(BuildTarget target) {
    return Paths.get(
        String.format(
            "%s/%s.jar",
            getOutputJarDirPath(target),
            target.getShortNameAndFlavorPostfix()));
  }

  /**
   * @return directory path relative to the project root where .class files will be generated.
   *     The return value does not end with a slash.
   */
  private static Path getClassesDir(BuildTarget target) {
    return BuildTargets.getScratchPath(target, "lib__%s__classes");
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public ImmutableSortedSet<Path> getJavaSrcs() {
    return ImmutableSortedSet.copyOf(getResolver().deprecatedAllPaths(srcs));
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return srcs;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDepsForTransitiveClasspathEntries() {
    return ImmutableSortedSet.copyOf(Sets.union(getDeclaredDeps(), exportedDeps));
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    return transitiveClasspathEntriesSupplier.get();
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return transitiveClasspathDepsSupplier.get();
  }

  /**
   * @return The set of entries to pass to {@code javac}'s {@code -classpath} flag in order to
   * compile the {@code srcs} associated with this rule.  This set only contains the classpath
   * entries for those rules that are declared as direct dependencies of this rule.
   */
  @VisibleForTesting
  ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries() {
    final ImmutableSetMultimap.Builder<JavaLibrary, Path> classpathEntries =
        ImmutableSetMultimap.builder();

    Iterable<JavaLibrary> javaLibraryDeps = JavaLibraryClasspathProvider.getJavaLibraryDeps(
        Iterables.concat(getDeclaredDeps(), exportedDeps, transitiveExportedDeps.get()));

    HashSet<JavaLibrary> seen = Sets.newHashSet();
    for (JavaLibrary rule : javaLibraryDeps) {
      if (seen.contains(rule)) {
        continue;
      }
      seen.add(rule);

      Optional<Path> classpathEntry = rule.getOutputClasspathEntry();
      if (classpathEntry.isPresent()) {
        classpathEntries.put(rule, classpathEntry.get());
      }
    }
    classpathEntries.putAll(this, additionalClasspathEntries);
    return classpathEntries.build();
  }

  @Override
  public Optional<Path> getOutputClasspathEntry() {
    return outputJar.transform(getProjectFilesystem().getAbsolutifier());
  }

  @Override
  public Optional<Path> getGeneratedSourcePath() {
    return generatedSourceFolder;
  }

  @Override
  public ImmutableSortedSet<BuildRule> getExportedDeps() {
    return exportedDeps;
  }

  /**
   * Building a java_library() rule entails compiling the .java files specified in the srcs
   * attribute. They are compiled into a directory under
   * {@link com.facebook.buck.util.BuckConstant#SCRATCH_DIR}.
   */
  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Only override the bootclasspath if this rule is supposed to compile Android code.
    ImmutableSetMultimap<JavaLibrary, Path> declaredClasspathEntries =
        getDeclaredClasspathEntries();

    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    BuildTarget target = getBuildTarget();
    Path outputDirectory = getClassesDir(target);
    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), outputDirectory));

    SuggestBuildRules suggestBuildRule =
        DefaultSuggestBuildRules.createSuggestBuildFunction(
            JAR_RESOLVER, declaredClasspathEntries,
            ImmutableSetMultimap.<JavaLibrary, Path>builder()
                .putAll(getTransitiveClasspathEntries())
                .putAll(this, additionalClasspathEntries)
                .build(),
            context.getActionGraph().getNodes());

    // We don't want to add these to the declared or transitive deps, since they're only used at
    // compile time.
    Collection<Path> provided = JavaLibraryClasspathProvider.getJavaLibraryDeps(providedDeps)
        .transformAndConcat(
            new Function<JavaLibrary, Collection<Path>>() {
              @Override
              public Collection<Path> apply(JavaLibrary input) {
                return input.getOutputClasspathEntry().asSet();
              }
            })
        .toSet();

    ImmutableSortedSet<Path> declared = ImmutableSortedSet.<Path>naturalOrder()
        .addAll(declaredClasspathEntries.values())
        .addAll(provided)
        .build();

    // Make sure that this directory exists because ABI information will be written here.
    Step mkdir = new MakeCleanDirectoryStep(getProjectFilesystem(), getPathToAbiOutputDir());
    steps.add(mkdir);

    // If there are resources, then link them to the appropriate place in the classes directory.
    JavaPackageFinder finder = context.getJavaPackageFinder();
    if (resourcesRoot.isPresent()) {
      finder = new ResourcesRootPackageFinder(resourcesRoot.get(), finder);
    }

    steps.add(
        new CopyResourcesStep(
            getProjectFilesystem(),
            getResolver(),
            target,
            resources,
            outputDirectory,
            finder));

    steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), getOutputJarDirPath(target)));

    // Only run javac if there are .java files to compile.
    if (!getJavaSrcs().isEmpty()) {
      // This adds the javac command, along with any supporting commands.
      Path pathToSrcsList = BuildTargets.getGenPath(getBuildTarget(), "__%s__srcs");
      steps.add(new MkdirStep(getProjectFilesystem(), pathToSrcsList.getParent()));

      Path scratchDir = BuildTargets.getGenPath(target, "lib__%s____working_directory");
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), scratchDir));
      Optional<Path> workingDirectory = Optional.of(scratchDir);

      compileStepFactory.createCompileToJarStep(
          context,
          getJavaSrcs(),
          target,
          getResolver(),
          getProjectFilesystem(),
          declared,
          outputDirectory,
          workingDirectory,
          Optional.of(pathToSrcsList),
          Optional.of(suggestBuildRule),
          postprocessClassesCommands,
          ImmutableSortedSet.of(outputDirectory),
          /* mainClass */ Optional.<String>absent(),
          /* manifestFile */ Optional.<Path>absent(),
          outputJar.get(),
          /* output params */
          steps,
          buildableContext);
    }


    Path abiJar = getOutputJarDirPath(target)
        .resolve(String.format("%s-abi.jar", target.getShortNameAndFlavorPostfix()));

    if (outputJar.isPresent()) {
      Path output = outputJar.get();

      // No source files, only resources
      if (getJavaSrcs().isEmpty()) {
        steps.add(
            new JarDirectoryStep(
                getProjectFilesystem(),
                output,
                ImmutableSortedSet.of(outputDirectory),
          /* mainClass */ null,
          /* manifestFile */ null));
      }
      buildableContext.recordArtifact(output);

      // Calculate the ABI.

      steps.add(new CalculateAbiStep(buildableContext, getProjectFilesystem(), output, abiJar));
    } else {
      Path scratch = BuildTargets.getScratchPath(
          target,
          String.format("%%s/%s-temp-abi.jar", target.getShortNameAndFlavorPostfix()));
      steps.add(new MakeCleanDirectoryStep(getProjectFilesystem(), scratch.getParent()));
      steps.add(new TouchStep(getProjectFilesystem(), scratch));
      steps.add(new CalculateAbiStep(buildableContext, getProjectFilesystem(), scratch, abiJar));
    }

    JavaLibraryRules.addAccumulateClassNamesStep(this, buildableContext, steps);

    return steps.build();
  }

  /**
   * Instructs this rule to report the ABI it has on disk as its current ABI.
   */
  @Override
  public JavaLibrary.Data initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    return JavaLibraryRules.initializeFromDisk(getBuildTarget(), onDiskBuildInfo);
  }

  @Override
  public BuildOutputInitializer<Data> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public Optional<SourcePath> getAbiJar() {
    return outputJar.isPresent() ? Optional.of(abiJar) : Optional.<SourcePath>absent();
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().getClassNamesToHashes();
  }

  @Override
  @Nullable
  public Path getPathToOutput() {
    return outputJar.orNull();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(ImmutableSortedSet.copyOf(
            Sets.difference(
                Sets.union(getDeclaredDeps(), exportedDeps),
                providedDeps)));
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (outputJar.isPresent()) {
      collector.addClasspathEntry(
          this,
          new BuildTargetSourcePath(getBuildTarget(), outputJar.get()));
    }
    if (proguardConfig.isPresent()) {
      collector.addProguardConfig(getBuildTarget(), proguardConfig.get());
    }
  }

}
