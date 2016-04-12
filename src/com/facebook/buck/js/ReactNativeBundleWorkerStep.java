/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.shell.WorkerJobParams;
import com.facebook.buck.shell.WorkerShellStep;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;

public class ReactNativeBundleWorkerStep extends WorkerShellStep {

  public ReactNativeBundleWorkerStep(
      final ProjectFilesystem filesystem,
      Path tmpDir,
      final Path jsPackager,
      Optional<String> additionalPackagerFlags,
      ReactNativePlatform platform,
      boolean isUnbundle,
      Path entryFile,
      boolean isDevMode,
      Path outputFile,
      Path resourcePath,
      Path sourceMapFile) {
    super(
        filesystem,
        filesystem.resolve(tmpDir),
        filesystem.getRootPath(),
        Optional.of(
            WorkerJobParams.of(
                Suppliers.memoize(new Supplier<Sha1HashCode>() {
                  @Override
                  public Sha1HashCode get() {
                    try {
                      return Sha1HashCode.of(filesystem.computeSha1(jsPackager));
                    } catch (IOException e) {
                      throw new RuntimeException(e);
                    }
                  }
                }),
                ImmutableList.of(jsPackager.toString()),
                String.format(
                    "--platform %s%s",
                    platform.toString(),
                    additionalPackagerFlags.isPresent() ? " " + additionalPackagerFlags.get() : ""),
                String.format(
                    "--command %s --entry-file %s --platform %s --dev %s --bundle-output %s " +
                        "--assets-dest %s --sourcemap-output %s",
                    isUnbundle ? "unbundle" : "bundle",
                    entryFile.toString(),
                    platform.toString(),
                    isDevMode ? "true" : "false",
                    outputFile.toString(),
                    resourcePath.toString(),
                    sourceMapFile.toString()))),
        Optional.<WorkerJobParams>absent(),
        Optional.<WorkerJobParams>absent());
  }

  @Override
  public String getShortName() {
    return "react-native-bundle-worker";
  }
}
