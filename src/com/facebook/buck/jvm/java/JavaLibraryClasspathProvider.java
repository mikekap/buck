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

package com.facebook.buck.jvm.java;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.ExportDependencies;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Sets;

import java.nio.file.Path;

public class JavaLibraryClasspathProvider {

  private JavaLibraryClasspathProvider() {
  }

  public static ImmutableSet<Path> getSuggestClasspathEntries(JavaLibrary javaLibraryRule) {
    ImmutableSet.Builder<Path> suggestClasspathBuilder = ImmutableSet.builder();
    Iterable<JavaLibrary> javaExportedLibraryDeps;
    if (javaLibraryRule instanceof ExportDependencies) {
      javaExportedLibraryDeps =
          getJavaLibraryDeps(((ExportDependencies) javaLibraryRule).getExportedDeps());
    } else {
      javaExportedLibraryDeps = Sets.newHashSet();
    }

    for (JavaLibrary rule : javaExportedLibraryDeps) {
      // If we have any exported deps, add an entry mapping ourselves to to their,
      // classpaths so when suggesting libraries to add we know that adding this library
      // would pull in it's deps.
      Optionals.addIfPresent(
          rule.getOutputClasspathEntry(),
          suggestClasspathBuilder
      );
    }

    Optionals.addIfPresent(
        javaLibraryRule.getOutputClasspathEntry(),
        suggestClasspathBuilder);
    return suggestClasspathBuilder.build();
  }

  public static ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries(
      JavaLibrary javaLibraryRule,
      SourcePathResolver resolver,
      Optional<SourcePath> outputJar) {
    final ImmutableSetMultimap.Builder<JavaLibrary, Path> classpathEntries =
        ImmutableSetMultimap.builder();
    ImmutableSetMultimap<JavaLibrary, Path> classpathEntriesForDeps =
        Classpaths.getClasspathEntries(javaLibraryRule.getDepsForTransitiveClasspathEntries());

    ImmutableSetMultimap<JavaLibrary, Path> classpathEntriesForExportedsDeps;
    if (javaLibraryRule instanceof ExportDependencies) {
      classpathEntriesForExportedsDeps =
          Classpaths.getClasspathEntries(((ExportDependencies) javaLibraryRule).getExportedDeps());
    } else {
      classpathEntriesForExportedsDeps = ImmutableSetMultimap.of();
    }

    classpathEntries.putAll(classpathEntriesForDeps);

    // If we have any exported deps, add an entry mapping ourselves to to their classpaths,
    // so when suggesting libraries to add we know that adding this library would pull in
    // it's deps.
    if (!classpathEntriesForExportedsDeps.isEmpty()) {
      classpathEntries.putAll(
          javaLibraryRule,
          classpathEntriesForExportedsDeps.values());
    }

    // Only add ourselves to the classpath if there's a jar to be built.
    if (outputJar.isPresent()) {
      classpathEntries.put(javaLibraryRule, resolver.getAbsolutePath(outputJar.get()));
    }

    return classpathEntries.build();
  }

  public static ImmutableSet<JavaLibrary> getTransitiveClasspathDeps(
      JavaLibrary javaLibrary,
      Optional<SourcePath> outputJar) {
    ImmutableSet.Builder<JavaLibrary> classpathDeps = ImmutableSet.builder();

    classpathDeps.addAll(
        Classpaths.getClasspathDeps(
            javaLibrary.getDepsForTransitiveClasspathEntries()));

    // Only add ourselves to the classpath if there's a jar to be built.
    if (outputJar.isPresent()) {
      classpathDeps.add(javaLibrary);
    }

    return classpathDeps.build();
  }

  static FluentIterable<JavaLibrary> getJavaLibraryDeps(Iterable<BuildRule> deps) {
    return FluentIterable.from(deps).filter(JavaLibrary.class);
  }
}
