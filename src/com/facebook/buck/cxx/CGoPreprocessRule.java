package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

/**
 * Created by mkaplinskiy on 1/26/16.
 */
public class CGoPreprocessRule extends AbstractBuildRule {
  @AddToRuleKey
  private final Tool cgo;
  @AddToRuleKey
  private final Compiler compiler;
  @AddToRuleKey(stringify = true)
  private final Path packageName;
  @AddToRuleKey
  private final ImmutableList<String> compilerFlags;
  @AddToRuleKey
  private final ImmutableList<SourcePath> input;

  private final Path output;

  protected CGoPreprocessRule(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Tool cgo,
      Compiler compiler,
      Path packageName,
      ImmutableList<String> compilerFlags,
      ImmutableList<SourcePath> input) {
    super(buildRuleParams, resolver);
    this.cgo = cgo;
    this.compiler = compiler;
    this.packageName = packageName;
    this.compilerFlags = compilerFlags;
    this.input = input;
    this.output = BuildTargets.getGenPath(getBuildTarget(), "%s_cgo_output");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList<String> cc = compiler.getCommandPrefix(getResolver());

    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output),
        new DefaultShellStep(
            getProjectFilesystem().getRootPath(),
            ImmutableList.<String>builder()
                .addAll(cgo.getCommandPrefix(getResolver()))
                .add("-objdir", this.output.toString())
                .add("-importpath", packageName.toString())
                .add("--")
                .addAll(cc.listIterator(1))
                .addAll(compilerFlags)
                .build(),
            ImmutableMap.of("CC", cc.get(0)))
    );
  }

  @Override
  public Path getPathToOutput() {
    return this.output;
  }
}
