# Genshin

Genshin is a Kotlin plugin for **Paper 1.21.11** that adds configurable elemental combat, reactions, elemental equipment, environmental effects, and region-aware gameplay. It requires **Java 21**.

## Features

- Seven elements: Anemo, Cryo, Dendro, Electro, Geo, Hydro, and Pyro.
- Elemental application from elemental damage, administrator commands, and ambient effects.
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
| `/genshin give <itemId> [player]` | `genshin.give` | Give a configured item. |
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
| `items.yml` | Item materials, types, weapon damage, visions, and lunar/stellar modifiers. |
| `placeholders.yml` | PlaceholderAPI text and display formats. |
| `bonuselementperclass.yml` | Optional MMOCore class-based elemental bonuses. |
| `regions/regions.yml` | Custom regions created with the region commands. |

Times are measured in server ticks (20 ticks per second) unless a setting explicitly says otherwise. Existing configuration files are not overwritten when the plugin updates; merge new defaults manually when needed.

### Items

Each item in `items.yml` defines `name`, `material`, `item-type`, `item-property`, `elemental-mastery`, and optional `lunar` or `stellar` settings. `item-type` is `physical`, `anemo`, `cryo`, `dendro`, `electro`, `geo`, `hydro`, or `pyro`. `item-property` is either `weapon` or `vision`.

A configured weapon uses its `damage` value as base damage on melee and projectile hits, before resistance and other event modifiers. Damage must be between 0 and 2048. A weapon's elemental item type applies on a successful hit. A physical weapon applies no element. A carried vision infuses the player's weapons, overriding their type, mastery, and celestial modifier. This includes configured weapons and vanilla swords, axes, bows, crossbows, tridents, and maces. If multiple visions are carried, the first in inventory slot order is used. Non-weapon items and unarmed attacks are not infused.

Mastery belongs to the wielder and is snapshotted at the hit, or at launch for projectiles. Equipment bonuses are included. When the same wielder applies both elements, the lower of the two snapshots is used. When different wielders are involved, the triggering wielder's snapshot is used; environmental triggers use the existing application snapshot. Reaction damage and Crystallize shield strength are multiplied by `1 + EM / (EM + 1050)`. Items show configured mastery in lore and glow above 50 EM. Bundled visions have 1 mastery and gnoses are visions with 100 mastery.

This is a breaking item configuration change: rename `damage-type` to `item-type`, add `item-property`, and set `damage` for weapons. Reload the plugin after editing. `/genshin register` creates a physical weapon definition from the held item's material. Custom reaction formulas retain their amount variables and also receive `elemental_mastery`. Resistances are configured under `damage.resistance` in `config.yml`.

For example:

```text
/genshin give vision_pyro
/genshin give gnosis_hydro PlayerName
```

### Lunar and stellar reactions

An item can enable either `lunar` or `stellar` as a top-level item section. The corresponding `bonus` increases the selected celestial reaction damage. Lunar changes Hydro paired with Geo, Dendro, or Electro; Stellar changes Cryo paired with Electro or Anemo. All other pairs retain their standard reactions.

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
