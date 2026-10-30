# Genshin

Genshin is a Kotlin plugin for **Paper 1.21.11** that adds configurable elemental combat, reactions, elemental equipment, environmental effects, and region-aware gameplay. It requires **Java 21**.

## Features

- Seven elements: Anemo, Cryo, Dendro, Electro, Geo, Hydro, and Pyro.
- Elemental application from configured items, melee attacks, bows, tridents, commands, ambient events, and reaction effects.
- Standard reactions: Frozen, Crystallize, Melt, Electro-Charged, Burning, Overload, Superconduct, Swirl, Vaporize, Quicken, Aggravate, Spread, Bloom, Hyperbloom, Burgeon, and Roots.
- Optional celestial reactions for items with a lunar or stellar modifier: Lunar Crystallize, Lunar Bloom, Lunar Charged, Stellar Conduct, and Stellar Swirl.
- Configurable reaction damage, per-element and physical resistance, formulas, cooldowns, durations, particles, and holograms.
- Artifact and armor lore effects, temporary elemental bonuses, and optional MMOCore class-level bonuses.
- Ambient Geo storms, Dendro breezes, and Pyro heat events, with custom-region and optional WorldGuard controls.
- Persistent item IDs, projectile element snapshots, custom regions, and PlaceholderAPI placeholders.

Reaction damage uses regular Paper damage events, preserving cancellation, attribution, protection plugins, and other server-side mitigation.

## Installation

1. Build the shaded plugin JAR.

   ```powershell
   .\gradlew.bat shadowJar
   ```

2. Copy `build/libs/genshin-1.0-SNAPSHOT-all.jar` to the server's `plugins/` directory.
3. Start the server once to create the default configuration files.
4. Configure the generated files, then run `/genshin reload` or restart the server.

The shaded `-all.jar` includes Kotlin. Do not install the plain JAR by itself.

## Commands and permissions

The main command is `/genshin`, with `/tg` as an alias. Administrative permissions default to operators; status commands are available to everyone by default.

| Command | Permission | Description |
| --- | --- | --- |
| `/genshin reload` | `genshin.reload` | Reload all configuration files after validating them. |
| `/genshin register` | `genshin.register` | Register the held named item in `items.yml`. |
| `/genshin give <itemId> <itemType> [player]` | `genshin.give` | Give a configured item. |
| `/genshin element <player> <element> <amount> <duration>` | `genshin.element` | Apply an element to an online player. |
| `/genshin putelement <element> <target\|area\|coneinfrontme> <range> <entities\|player> [amount]` | `genshin.putelement` | Apply an element from the command sender. |
| `/genshin bonuselement <element> <multiplier> <playerName\|players> [duration]` | `genshin.bonuselement` | Grant a temporary elemental multiplier. |
| `/genshin armorstatus` | `genshin.armorstatus` | Show active armor effects. |
| `/genshin artifactstatus` | `genshin.artifactstatus` | Show active artifact effects and bonuses. |
| `/genshin removeholo [radius]` | `genshin.removeholo` | Remove nearby plugin holograms. |
| `/genshin pos1`, `/genshin pos2`, `/genshin createregion <name>` | `genshin.regions` | Define and save a custom region. |

`genshin.*` grants every plugin permission. Durations accept tick or second notation such as `100t` or `10s`.

## Configuration

The plugin bundles these files and only creates missing copies on startup:

| File | Purpose |
| --- | --- |
| `config.yml` | Element state duration, reactions, damage, formulas, visual effects, ambient events, protection, armor, artifacts, and general settings. |
| `items.yml` | Item names, damage type, elemental application, and lunar/stellar modifiers. |
| `placeholders.yml` | PlaceholderAPI text and display formats. |
| `bonuselementperclass.yml` | Optional MMOCore class-based elemental bonuses. |
| `regions/regions.yml` | Custom regions created with the region commands. |

Times are measured in server ticks (20 ticks per second) unless a setting explicitly says otherwise. Existing configuration files are not overwritten when the plugin updates; merge new defaults manually when needed.

### Items

Each entry in `items.yml` defines a display name, optional `damage-type`, and the element it applies. Exactly one standard element should be enabled. `damage-type` is independent of elemental application and may be `physical`, `anemo`, `cryo`, `dendro`, `electro`, `geo`, `hydro`, or `pyro`.

The default item set includes one Vision and one Gnosis for every element:

- `vision_anemo` through `vision_pyro` apply 1 unit of their matching element.
- `gnosis_anemo` through `gnosis_pyro` apply 10 units of their matching element.
- Visions and Gnoses have both `lunar` and `stellar` modifiers disabled.

For example:

```text
/genshin give vision_pyro staff
/genshin give gnosis_hydro trident PlayerName
```

`/genshin register` creates a disabled definition for the item in the command sender's main hand. Enable one element, set its `amount`, and reload the plugin. Items given by the plugin keep a persistent ID; legacy items can also be matched by their formatted display name.

### Lunar and stellar reactions

An item can enable either `lunar` or `stellar` under its `elements` section. The corresponding `bonus` increases the selected celestial reaction damage. Lunar changes Hydro paired with Geo, Dendro, or Electro; Stellar changes Cryo paired with Electro or Anemo. All other pairs retain their standard reactions.

## Optional integrations

The plugin starts without optional dependencies.

- **WorldGuard:** respects PvP and ambient restrictions. It registers `genshin-ambient-storm`, `pyro-heat`, `dendro-breeze`, and `geo-storm` flags.
- **WorldEdit:** used by WorldGuard when querying protected regions.
- **Citizens:** NPCs are excluded from normal protection checks.
- **MMOCore:** supplies class and level data for `bonuselementperclass.yml` bonuses.
- **PlaceholderAPI:** exposes `%genshin_...%` placeholders configured in `placeholders.yml`.

## Development

```powershell
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat runPaperSmoke
```

`test` runs the unit and MockBukkit regression tests. `build` also produces the distributable shaded JAR. `runPaperSmoke` starts a disposable Paper server under `build/paper-smoke`, runs runtime combat and lifecycle checks, then stops the server. Use `runServer` for a regular development server in `run/`.
