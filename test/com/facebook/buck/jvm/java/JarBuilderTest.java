/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.zip.CustomZipEntry;
import com.facebook.buck.util.zip.JarBuilder;
import com.facebook.buck.util.zip.JarEntryContainer;
import com.facebook.buck.util.zip.JarEntrySupplier;
import com.facebook.buck.util.zip.ZipConstants;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JarBuilderTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testSortsEntriesFromAllContainers() throws IOException {
    File tempFile = temporaryFolder.newFile();
    try (TestJarEntryContainer container1 = new TestJarEntryContainer("Container1");
        TestJarEntryContainer container2 = new TestJarEntryContainer("Container2");
        TestJarEntryContainer container3 = new TestJarEntryContainer("Container3")) {
      new JarBuilder()
          .addEntryContainer(container1.addEntry("Foo", "Foo").addEntry("Bar", "Bar"))
          .addEntryContainer(
              container2.addEntry("Bird", "Bird").addEntry("Dog", "Dog").addEntry("Cat", "Cat"))
          .addEntryContainer(
              container3
                  .addEntry("A", "A")
                  .addEntry("B", "B")
                  .addEntry("C", "C")
                  .addEntry("D", "D"))
          .createJarFile(tempFile.toPath());
    }

    try (JarFile jarFile = new JarFile(tempFile)) {
      assertEquals(
          ImmutableList.of(
              "META-INF/",
              "META-INF/MANIFEST.MF",
              "A",
              "B",
              "Bar",
              "Bird",
              "C",
              "Cat",
              "D",
              "Dog",
              "Foo"),
          jarFile.stream().map(JarEntry::getName).collect(Collectors.toList()));
    }
  }

  @Test
  public void testRecompression() throws IOException {
    File tempFile = temporaryFolder.newFile();
    try (TestJarEntryContainer container1 = new TestJarEntryContainer("Container1")) {
      new JarBuilder()
          .setCompressionLevel(0)
          .addEntryContainer(container1.addEntry("Foo", "Foo").addEntry("Bar", "Bar"))
          .createJarFile(tempFile.toPath());
    }

    try (JarFile jarFile = new JarFile(tempFile)) {
      assertEquals(
          ImmutableList.of(
              new Pair<>("META-INF/", 2L),
              new Pair<>("META-INF/MANIFEST.MF", 27L),
              new Pair<>("Bar", 3L),
              new Pair<>("Foo", 3L)),
          jarFile.stream()
              .map(v -> new Pair<>(v.getName(), v.getCompressedSize()))
              .collect(Collectors.toList()));
    }
  }

  @Test
  public void testDisallowAllDuplicates() throws IOException {
    File tempFile = temporaryFolder.newFile();
    JarBuilder builder;
    try (TestJarEntryContainer container1 = new TestJarEntryContainer("Container1");
        TestJarEntryContainer container2 = new TestJarEntryContainer("Container2")) {
      builder =
          new JarBuilder()
              .addEntryContainer(
                  container1
                      .addEntry("Foo.class", "Foo1")
                      .addEntry("Bar.class", "Bar1")
                      .addEntry("Buz.txt", "Buz1"))
              .addEntryContainer(
                  container2
                      .addEntry("Foo.class", "Foo2")
                      .addEntry("Fiz.class", "Fiz2")
                      .addEntry("Buz.txt", "Buz2"));
    }

    builder.createJarFile(tempFile.toPath());
    try (JarFile jarFile = new JarFile(tempFile)) {
      assertEquals(
          ImmutableList.of(
              "META-INF/",
              "META-INF/MANIFEST.MF",
              "Bar.class",
              "Buz.txt",
              "Buz.txt",
              "Fiz.class",
              "Foo.class"),
          jarFile.stream().map(JarEntry::getName).collect(Collectors.toList()));
    }

    try (TestJarEntryContainer container1 = new TestJarEntryContainer("Container1");
        TestJarEntryContainer container2 = new TestJarEntryContainer("Container2")) {
      builder =
          new JarBuilder()
              .addEntryContainer(
                  container1
                      .addEntry("Foo.class", "Foo1")
                      .addEntry("Bar.class", "Bar1")
                      .addEntry("Buz.txt", "Buz1"))
              .addEntryContainer(
                  container2
                      .addEntry("Foo.class", "Foo2")
                      .addEntry("Fiz.class", "Fiz2")
                      .addEntry("Buz.txt", "Buz2"));
    }

    builder.setShouldDisallowAllDuplicates(true);
    builder.createJarFile(tempFile.toPath());
    try (JarFile jarFile = new JarFile(tempFile)) {
      assertEquals(
          ImmutableList.of(
              "META-INF/",
              "META-INF/MANIFEST.MF",
              "Bar.class",
              "Buz.txt",
              "Fiz.class",
              "Foo.class"),
          jarFile.stream().map(JarEntry::getName).collect(Collectors.toList()));
    }
  }

  @Test
  public void testMakesDirectoriesForEntries() throws IOException {
    File tempFile = temporaryFolder.newFile();
    JarBuilder jarBuilder = new JarBuilder();
    addEntry(jarBuilder, "foo/1.txt", "1");
    addEntry(jarBuilder, "foo/2.txt", "2");
    addEntry(jarBuilder, "foo/bar/3.txt", "3");
    jarBuilder.createJarFile(tempFile.toPath());

    try (JarFile jarFile = new JarFile(tempFile)) {
      assertEquals(
          ImmutableList.of(
              "META-INF/",
              "META-INF/MANIFEST.MF",
              "foo/",
              "foo/1.txt",
              "foo/2.txt",
              "foo/bar/",
              "foo/bar/3.txt"),
          jarFile.stream().map(JarEntry::getName).collect(Collectors.toList()));
    }
  }

  private void addEntry(JarBuilder builder, String name, String contents) {
    builder.addEntry(
        new JarEntrySupplier(
            new CustomZipEntry(name),
            "owner",
            () -> new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  public void testMergesServicesFromAllContainers() throws IOException {
    for (boolean shouldDisallowAllDuplicates : new boolean[] {false, true}) {
      File tempFile = temporaryFolder.newFile();

      try (TestJarEntryContainer container1 = new TestJarEntryContainer("Container1");
          TestJarEntryContainer container2 = new TestJarEntryContainer("Container2");
          TestJarEntryContainer container3 = new TestJarEntryContainer("Container3")) {
        new JarBuilder()
            .addEntryContainer(
                container1.addEntry("META-INF/services/com.example.Foo1", "com.example.Bar2"))
            .addEntryContainer(
                container2
                    .addEntry("META-INF/services/com.example.Foo1", "com.example.Bar1")
                    .addEntry("META-INF/services/com.example.Foo2", "com.example.Bar3")
                    .addEntry("META-INF/services/com.example.Foo2", "com.example.Bar4"))
            .addEntryContainer(
                container3
                    .addEntry("META-INF/services/com.example.Foo2", "com.example.Bar3")
                    .addEntry("META-INF/services/foo/bar", "bar"))
            .setShouldDisallowAllDuplicates(shouldDisallowAllDuplicates)
            .createJarFile(tempFile.toPath());
      }

      try (JarFile jarFile = new JarFile(tempFile)) {

        // Test ordering
        assertEquals(
            "com.example.Bar2\ncom.example.Bar1",
            CharStreams.toString(
                new InputStreamReader(
                    jarFile.getInputStream(jarFile.getEntry("META-INF/services/com.example.Foo1")),
                    Charsets.UTF_8)));

        // Test duplication
        assertEquals(
            "com.example.Bar3\ncom.example.Bar4",
            CharStreams.toString(
                new InputStreamReader(
                    jarFile.getInputStream(jarFile.getEntry("META-INF/services/com.example.Foo2")),
                    Charsets.UTF_8)));

        // Test non service files
        assertEquals(
            ImmutableList.of(
                "META-INF/",
                "META-INF/MANIFEST.MF",
                "META-INF/services/",
                "META-INF/services/foo/",
                "META-INF/services/com.example.Foo1",
                "META-INF/services/foo/bar",
                "META-INF/services/com.example.Foo2"),
            jarFile.stream().map(JarEntry::getName).collect(Collectors.toList()));
      }
    }
  }

  @Test
  public void testDoesNotLeakJarFileHandles() throws Exception {
    File toTest = temporaryFolder.newFile();
    File modification = temporaryFolder.newFile();
    try (TestJarEntryContainer container1 = new TestJarEntryContainer("Container1");
        TestJarEntryContainer container2 = new TestJarEntryContainer("Container2")) {
      new JarBuilder()
          .addEntryContainer(container1.addEntry("Before", "Before"))
          .setShouldHashEntries(true)
          .createJarFile(toTest.toPath());
      new JarBuilder()
          .addEntryContainer(container2.addEntry("After", "After"))
          .setShouldHashEntries(true)
          .createJarFile(modification.toPath());
    }

    FileTime hardcodedTime = FileTime.fromMillis(ZipConstants.getFakeTime());
    Files.setLastModifiedTime(toTest.toPath(), hardcodedTime);
    Files.setLastModifiedTime(modification.toPath(), hardcodedTime);

    // Use JarBuilder with toTest
    File outputFile = temporaryFolder.newFile();
    new JarBuilder()
        .setEntriesToJar(ImmutableList.of(toTest.toPath()))
        .setShouldHashEntries(true)
        .setShouldMergeManifests(true)
        .createJarFile(outputFile.toPath());
    // assert the jar was created
    assertTrue(Files.exists(outputFile.toPath()));

    // Now modify toTest make sure we don't get a cached result when we open it for a second time
    Files.move(modification.toPath(), toTest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    Files.setLastModifiedTime(toTest.toPath(), hardcodedTime);

    Map<String, Attributes> entries = new JarFile(toTest).getManifest().getEntries();
    // If we leaked the file handle for toTest from within JarBuilder then we will see
    // stale data here (or a crash) and the manifest
    // will incorrectly return "Before" instead of "After"
    // See item (3) of https://bugs.openjdk.java.net/browse/JDK-8142508 for some info
    assertThat(entries.keySet(), Matchers.contains("After"));
  }

  private static class TestJarEntryContainer implements JarEntryContainer {
    @Nullable private Manifest manifest;
    private final List<JarEntrySupplier> suppliers = new ArrayList<>();
    private final String containerName;

    private TestJarEntryContainer(String containerName) {
      this.containerName = containerName;
    }

    public TestJarEntryContainer addEntry(String name, String contents) {
      suppliers.add(
          new JarEntrySupplier(
              new CustomZipEntry(name),
              containerName,
              () -> new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8))));
      return this;
    }

    @Nullable
    @Override
    public Manifest getManifest() {
      return manifest;
    }

    @Override
    public Stream<JarEntrySupplier> stream() {
      return suppliers.stream();
    }

    @Override
    public void close() {}
  }
}
