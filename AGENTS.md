# Repository Guidelines

## Project scope

- The active plugin is the Kotlin source and resources under `src/`; maintain only that implementation and the root Gradle build files.
- Target Paper 1.21.11 and Java 21. Keep optional integrations optional: the plugin must start without WorldGuard, WorldEdit, Citizens, MMOCore, or PlaceholderAPI.
- Use English for source code, configuration, item definitions, documentation, command messages, and user-facing text.

## Code and configuration

- Keep packages under `org.plary.genshin` and use Kotlin idioms consistent with nearby code.
- Preserve the separation between runtime logic in `src/main/` and smoke-test-only code in `src/test/`; do not package test plugins into the production JAR.
- Treat `config.yml`, `items.yml`, `placeholders.yml`, `bonuselementperclass.yml`, and `regions/regions.yml` as public configuration contracts. Retain backward-compatible keys unless a migration is included.
- Item definitions should enable at most one standard element. Lunar and stellar modifiers are mutually exclusive.
- Keep damage, permissions, protection checks, cooldowns, and configuration bounds explicit when adding combat behavior.

## Validation

- Run the relevant Gradle task for every functional change: `./gradlew.bat test` for unit-level changes, `./gradlew.bat build` for packaging, and `./gradlew.bat runPaperSmoke` for Paper runtime behavior.
- If the full suite is blocked by unrelated existing failures, run the focused relevant tests and state the limitation clearly.
- Do not commit generated `build/`, `run/`, or server runtime files.
