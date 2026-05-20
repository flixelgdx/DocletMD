/*
 * Copyright (c) 2026 String
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package me.stringdotjar.docletmd;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocletMDPluginTest {

  @TempDir
  File tempDir;

  /**
   * Verifies that a simple class with full Javadoc produces a correctly structured
   * Markdown file with frontmatter, class heading, method heading, and param table.
   */
  @Test
  void generateDocletMD_basicClass_producesMarkdown() throws Exception {
    writeFile("settings.gradle", "rootProject.name = 'test-project'\n");
    writeFile("build.gradle", """
        plugins {
            id 'java'
            id 'me.stringdotjar.docletmd'
        }
        java {
            toolchain { languageVersion = JavaLanguageVersion.of(21) }
        }
        """);

    Path srcDir = tempDir.toPath().resolve("src/main/java/com/example");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("Calculator.java"), """
        package com.example;

        /**
         * A simple calculator that adds numbers.
         *
         * @since 1.0
         */
        public class Calculator {

            /**
             * Adds two integers.
             *
             * @param a the first operand
             * @param b the second operand
             * @return the sum of a and b
             */
            public int add(int a, int b) {
                return a + b;
            }
        }
        """);

    BuildResult result = runner("generateDocletMD", "--info").build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":generateDocletMD").getOutcome());

    Path mdFile = tempDir.toPath().resolve("build/docletmd/com/example/Calculator.md");
    assertTrue(mdFile.toFile().exists(), "Expected Markdown file not found: " + mdFile);

    String content = Files.readString(mdFile);
    assertTrue(content.contains("title: Calculator"), "Missing frontmatter title");
    assertTrue(content.contains("# Calculator"), "Missing H1 heading");
    assertTrue(content.contains("`com.example.Calculator`"), "Missing qualified name");
    assertTrue(content.contains("A simple calculator"), "Missing class description");
    assertTrue(content.contains("**Since:** 1.0"), "Missing @since tag");
    assertTrue(content.contains("### `int add(int a, int b)`"), "Missing method heading");
    assertTrue(content.contains("Adds two integers"), "Missing method description");
    assertTrue(content.contains("| `a` |"), "Missing param table entry for 'a'");
    assertTrue(content.contains("| `b` |"), "Missing param table entry for 'b'");
    assertTrue(content.contains("**Returns:**"), "Missing @return section");
  }

  /**
   * Verifies that a class annotated with {@code @Deprecated} produces a
   * Docusaurus {@code :::caution Deprecated} admonition block.
   */
  @Test
  void generateDocletMD_deprecatedClass_producesAdmonition() throws Exception {
    writeFile("settings.gradle", "rootProject.name = 'test-project'\n");
    writeFile("build.gradle", """
        plugins {
            id 'java'
            id 'me.stringdotjar.docletmd'
        }
        java {
            toolchain { languageVersion = JavaLanguageVersion.of(21) }
        }
        """);

    Path srcDir = tempDir.toPath().resolve("src/main/java/com/example");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("OldApi.java"), """
        package com.example;

        /**
         * An old API.
         *
         * @deprecated Use {@link Calculator} instead.
         */
        @Deprecated
        public class OldApi {}
        """);

    BuildResult result = runner("generateDocletMD").build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":generateDocletMD").getOutcome());

    Path mdFile = tempDir.toPath().resolve("build/docletmd/com/example/OldApi.md");
    assertTrue(mdFile.toFile().exists(), "Expected Markdown file not found: " + mdFile);

    String content = Files.readString(mdFile);
    assertTrue(content.contains(":::caution Deprecated"), "Missing deprecation admonition");
    assertTrue(content.contains(":::"), "Admonition not closed");
  }

  /**
   * Verifies that the task produces no output (and does not fail) when there are
   * no Java source files in the configured source directories.
   */
  @Test
  void generateDocletMD_noSources_skips() throws Exception {
    writeFile("settings.gradle", "rootProject.name = 'test-project'\n");
    writeFile("build.gradle", """
        plugins {
            id 'java'
            id 'me.stringdotjar.docletmd'
        }
        java {
            toolchain { languageVersion = JavaLanguageVersion.of(21) }
        }
        """);

    // No source files created -- src/main/java does not exist

    BuildResult result = runner("generateDocletMD").build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":generateDocletMD").getOutcome());
  }

  private GradleRunner runner(String... args) {
    return GradleRunner.create()
        .withProjectDir(tempDir)
        .withArguments(args)
        .withPluginClasspath()
        .forwardOutput();
  }

  private void writeFile(String relativePath, String content) throws Exception {
    Path file = tempDir.toPath().resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
