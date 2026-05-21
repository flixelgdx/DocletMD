# DocletMD

A Gradle plugin that converts your Javadoc comments into Docusaurus-ready Markdown files.

Each public class, interface, enum, and record gets its own `.md` file, organized by package. Fields, constructors, and methods are all documented, including their modifiers, parameter tables, return values, throws tables, and deprecation notices. Inline tags like `{@link}` and `{@code}` are converted to Markdown links and code spans. HTML in your Javadoc (lists, tables, bold, `<pre>` blocks) is converted as well.

## Requirements

- Java 21 or later
- Gradle 8 or later

## Installation

DocletMD is published via [JitPack](https://jitpack.io). Add the JitPack repository and a resolution rule to your `settings.gradle` (or `settings.gradle.kts`) so Gradle knows where to find the plugin:

**`settings.gradle`**
```groovy
pluginManagement {
    repositories {
        maven { url 'https://jitpack.io' }
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == 'me.stringdotjar.docletmd') {
                useModule('com.github.flixelgdx.DocletMD:DocletMD:0.1.0')
            }
        }
    }
}
```

> Replace `0.1.0` with any released tag from the [releases page](https://github.com/flixelgdx/DocletMD/releases),
> or use a full commit hash for a snapshot build.

Then apply the plugin alongside the `java` plugin in your `build.gradle`:

**`build.gradle`**
```groovy
plugins {
    id 'java'
    id 'me.stringdotjar.docletmd' version '0.1.0'
}
```

### Alternative: `buildscript` block

If you prefer the legacy approach, skip the `pluginManagement` block and use a `buildscript` classpath instead:

**`build.gradle`**
```groovy
buildscript {
    repositories {
        maven { url 'https://jitpack.io' }
    }
    dependencies {
        classpath 'com.github.flixelgdx.DocletMD:DocletMD:0.1.0'
    }
}

apply plugin: 'java'
apply plugin: 'me.stringdotjar.docletmd'
```

## Configuration

All settings are optional. Add a `docletmd {}` block to `build.gradle` to override the defaults:

```groovy
docletmd {
    // Where the generated Markdown files are written.
    // Default: build/docletmd
    outputDir = file("docs/api")

    // Set to true to also document private and package-private members.
    // Default: false
    includePrivate = false

    // Set to true to skip members that have no Javadoc comment at all.
    // Default: false
    skipEmptyDocs = false
}
```

## Usage

Run the `generateDocletMD` task:

```bash
./gradlew generateDocletMD
```

The task is also available in the **documentation** group in the Gradle task list.

## Output

Each class produces one `.md` file, placed under `outputDir` in a directory tree that mirrors the package structure. For example, a class `com.example.MyService` produces:

```
build/docletmd/
  com/
    example/
      MyService.md
```

Every file contains:

- A YAML frontmatter block with `title` and `sidebar_label` (compatible with Docusaurus).
- An H1 heading with the class name.
- The full qualified name as an inline code span.
- An optional `:::caution Deprecated` admonition when the class is deprecated.
- The Javadoc description, including inline `{@link}` and `{@code}` tags.
- `@since` and `@see` meta-tags.
- Sections for **Constructors**, **Fields**, and **Methods**, each with:
  - The full signature (modifiers, return type, parameters, constant value for `final` fields).
  - The Javadoc description.
  - Parameter table (`@param` tags).
  - Return value (`@return` tag).
  - Throws table (`@throws` / `@exception` tags).

## Docusaurus integration

Point Docusaurus at the output directory by adding it as a docs directory or a plugin source in your `docusaurus.config.js`. Because each file has valid frontmatter, Docusaurus picks up the `title` and `sidebar_label` automatically.

```js
// docusaurus.config.js (example)
const config = {
  // ...
  presets: [
    [
      'classic',
      {
        docs: {
          path: 'docs',
          // If your outputDir points inside docs/, nothing extra is needed.
        },
      },
    ],
  ],
};
```

If your `outputDir` is outside the Docusaurus `docs/` folder, copy or symlink the output after running `generateDocletMD`, or wire the two Gradle tasks together:

```groovy
tasks.named('generateDocletMD').configure {
    finalizedBy(tasks.named('copyApiDocs'))
}

tasks.register('copyApiDocs', Copy) {
    from docletmd.outputDir
    into 'path/to/docusaurus/docs/api'
    dependsOn generateDocletMD
}
```

## License

MIT
