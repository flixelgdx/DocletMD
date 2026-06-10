# DocletMD — project instructions for Claude Code

---

## Project context (DocletMD)

This is a plugin that aims to convert Javadoc comments into a usable and customizable format that Docusaurus can consume without issues.

---

## Collaboration before implementation

Treat the interaction as teamwork, not robotic task execution. Prefer brainstorming when the user's direction is ambiguous.

Before implementing anything (planning or coding):

1. Ask yourself whether the requested design or refactor is actually good for the plugin.
2. If it could hurt the plugin (or people's projects), breaks invariants, or there is clearly a better path, **stop before editing files or running commands**.
3. Explain why (pros and cons), suggest better alternatives, and ask whether the user still wants to proceed.
4. If they confirm after that discussion, proceed as requested. 

---

## Explaining things for beginners and contributors

When explaining code or introducing patterns:

- Explain **why** before **how** (motivation before mechanics).
- Use analogies for complex systems; if the user gave no analogy topic, ask for one they like.
- End **complex** explanations with a short check-in question so you can verify understanding.
- Stay encouraging and professional. Assume intelligence but not deep familiarity with certain systems.

---

## Code quality (non-negotiables)

### Language and style

- Target **Java 17**. Prefer modern features (records, lambdas, modern `switch`) over legacy patterns.
- **Import** every type you use. Do **not** use star imports (`*`).
- Do **not** use fully qualified class names inline when a normal import would read cleanly.
- Empty or void-returning methods: opening brace on the same line per `.editorconfig` (example: `public void hook() {}`).
- Prefer **short** field and method names. If shortening a method name hides meaning, use a concise name plus Javadoc instead of a long identifier.

### Finishing work

Summarize edits in plain language: what changed, why, and how it fits the system.

Before considering a coding task finished, **run unit tests** and **Javadoc lint**; fix failures.

---

## Documentation, comments, and Javadoc

Documentation should read like a **beginner-friendly handbook**, not an expert-only manual.

### Mechanics

- Use correct grammar and punctuation everywhere (comments, `@param`, `@return`, `@throws`, and so on).
- Stick to **ASCII** in prose when practical; avoid decorative punctuation like en dash, fancy arrows or emojis. This allows the
  docs to be read easily on every device and requires you to use clarity over brevity.
- Include the right Javadoc tags (`@param`, `@return`, `@throws`, …) wherever they apply.
- Use nullability annotations (`@Nullable`, `@NotNull`) where they help tooling.
- Keep `@link` references valid or fix broken links.
- Follow `.editorconfig` for formatting.
- Add comments where either complexity would otherwise be hard to follow, or where code requires import context.
- Skip Javadoc on trivial, self-explanatory methods (plain getters/setters or something like `calculateTotal()` unless there is subtle behavior).
- Prefer **American English** in docs.
- After code changes that affect public behavior or APIs, **update relevant Markdown docs** in the repo.

### Comments versus Javadocs

- **In line comments**, do **not** use Markdown tricks (bold with `**`, backticks around snippets, etc.). Reserve richer formatting for Javadoc.
- When naming methods inside comments, include parentheses (`someMethod()`). If parameters exist but you are not spelling them out, use `anotherMethod(...)`. Example class-qualified form: `SomeClass.someMethod(...)`.
- **In comments**, prefer `SomeClass.someMethod(...)` instead of Javadoc-style `SomeClass#someMethod(int)`.

### Heavily used or critical APIs

For widely used classes, fields, or methods — or anything central to correctness — include a **small usage example** in Javadoc showing correct typical use.

---

## Working with Git and Pull Requests

- Prefer **small, focused commits** as you finish logical slices of work so history stays readable.
- Use **one branch and one pull request** unless the user explicitly asks for more (for example stacked features or dependent work).
- If the user renames a pull request, **do not rename it back**; respect their title.
