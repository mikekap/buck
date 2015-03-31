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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Set;

public class CxxExtensionLibraryDescription implements
    Description<CxxExtensionLibraryDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<CxxExtensionLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("cxx_extension_library");

  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public CxxExtensionLibraryDescription(
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.cxxBuckConfig = cxxBuckConfig;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @VisibleForTesting
  protected String getExtensionName(BuildTarget target, CxxPlatform cxxPlatform) {
    return String.format("%s.%s", target.getShortName(), cxxPlatform.getSharedLibraryExtension());
  }

  @VisibleForTesting
  protected Path getExtensionPath(BuildTarget target, String soname) {
    return BuildTargets.getScratchPath(target, "%s").resolve(soname);
  }

  private <A extends Arg> BuildRule createExtensionBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CxxPlatform cxxPlatform,
      A args) {

    String extensionName = args.soname.or(getExtensionName(params.getBuildTarget(), cxxPlatform));
    Path extensionPath = getExtensionPath(params.getBuildTarget(), extensionName);

    return CxxDescriptionEnhancer.createBuildRulesForCxxConstructorArg(
        params,
        ruleResolver,
        cxxPlatform,
        args,
        CxxSourceRuleFactory.Strategy.SEPARATE_PREPROCESS_AND_COMPILE,
        CxxSourceRuleFactory.PicType.PIC,
        Linker.LinkableDepType.STATIC,
        Linker.LinkType.SHARED,
        params.getBuildTarget(),
        Optional.of(extensionName),
        extensionPath);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      A args) {

    // Extract the platform from the flavor, falling back to the default platform if none are
    // found.
    CxxPlatform cxxPlatform;
    ImmutableSet<Flavor> flavors = ImmutableSet.copyOf(params.getBuildTarget().getFlavors());
    try {
      cxxPlatform = cxxPlatforms
          .getValue(flavors)
          .or(defaultCxxPlatform);
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", params.getBuildTarget(), e.getMessage());
    }

    return createExtensionBuildRule(params, ruleResolver, cxxPlatform, args);
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

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> inputFlavors) {
    Set<Flavor> flavors = inputFlavors;

    Set<Flavor> platformFlavors = Sets.intersection(flavors, cxxPlatforms.getFlavors());
    if (platformFlavors.size() > 1) {
      return false;
    }
    flavors = Sets.difference(flavors, platformFlavors);

    return flavors.isEmpty();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<String> soname;
  }

}
