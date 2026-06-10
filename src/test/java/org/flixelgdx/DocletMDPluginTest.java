package org.flixelgdx;

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
            id 'org.flixelgdx.docletmd'
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
    assertTrue(content.contains("### `public int add(int a, int b)`"), "Missing method heading");
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
            id 'org.flixelgdx.docletmd'
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
   * Verifies that {@code {@inheritDoc}} in a method body, {@code @param}, and
   * {@code @return} is expanded with the overridden method's documentation.
   */
  @Test
  void generateDocletMD_inheritDoc_copiesParentJavadoc() throws Exception {
    writeFile("settings.gradle", "rootProject.name = 'test-project'\n");
    writeFile("build.gradle", """
        plugins {
            id 'java'
            id 'org.flixelgdx.docletmd'
        }
        java {
            toolchain { languageVersion = JavaLanguageVersion.of(21) }
        }
        """);

    Path srcDir = tempDir.toPath().resolve("src/main/java/com/example");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("Shape.java"), """
        package com.example;

        /** A geometric shape. */
        public interface Shape {

            /**
             * Scales the shape by a factor.
             *
             * @param factor the multiplier applied to every dimension
             * @return the resulting area after scaling
             */
            double scale(double factor);
        }
        """);
    Files.writeString(srcDir.resolve("Square.java"), """
        package com.example;

        /** A four-sided shape. */
        public class Square implements Shape {

            /**
             * {@inheritDoc}
             *
             * @param factor {@inheritDoc}
             * @return {@inheritDoc}
             */
            @Override
            public double scale(double factor) {
                return factor * factor;
            }
        }
        """);

    BuildResult result = runner("generateDocletMD").build();

    assertEquals(TaskOutcome.SUCCESS, result.task(":generateDocletMD").getOutcome());

    Path mdFile = tempDir.toPath().resolve("build/docletmd/com/example/Square.md");
    assertTrue(mdFile.toFile().exists(), "Expected Markdown file not found: " + mdFile);

    String content = Files.readString(mdFile);
    assertTrue(content.contains("Scales the shape by a factor"),
        "Inherited body description was not copied from the overridden method");
    assertTrue(content.contains("the multiplier applied to every dimension"),
        "Inherited @param description was not copied");
    assertTrue(content.contains("the resulting area after scaling"),
        "Inherited @return description was not copied");
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
            id 'org.flixelgdx.docletmd'
        }
        java {
            toolchain { languageVersion = JavaLanguageVersion.of(21) }
        }
        """);

    // No source files are created here; src/main/java does not exist.

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
