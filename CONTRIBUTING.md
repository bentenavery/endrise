# Contributing to Endrise

Welcome. This is a hobby mod with a real roadmap; pull requests, ideas, and playtesting all count as contributing.

## Dev quickstart

```bash
git clone <this repo>
cd endrise
./gradlew runClient
```

That is the whole setup. Gradle auto-provisions the Java 25 compile toolchain. You need any JDK 17-26 installed for Gradle itself to run.

**Do not downgrade the Gradle wrapper.** It is pinned to 9.5.1 on purpose; the MDK's original 9.2.1 cannot run on newer JDKs (fails with "Unsupported class file major version").

Useful tasks:

- `./gradlew runClient` — dev client with the mod loaded
- `./gradlew runServer` — dedicated server; this is also the fastest validator for data files (worldgen, loot, recipes all parse at boot)
- `./gradlew build` — distributable jar in `build/libs/`

## Where things live

- `src/main/java/ai/cendrix/endrise/` — registration and logic. `Endrise.java` is the entry point.
- `src/main/resources/assets/endrise/` — client visuals: blockstates, models, textures, lang. Note: modern Minecraft needs both a model *and* a client item definition in `items/`.
- `src/main/resources/data/endrise/` — behavior: loot tables, recipes, worldgen.
- `data/endrise/neoforge/biome_modifier/` — where features get injected into biomes (this is how the ore reaches every `#minecraft:is_end` biome).

## Format truth

Tutorials and wikis drift behind Minecraft versions. When you need the current shape of any JSON (loot, recipes, worldgen), crib from the real thing: the vanilla client jar in your Gradle cache (`~/.gradle/caches/neoformruntime/artifacts/minecraft_<version>_client.jar`) contains every vanilla `assets/` and `data/` file for this exact version. Copy vanilla's format, then adapt.

## Art guidelines

- 16x16, vanilla-adjacent saturation. Ore textures build on the host stone's actual texture with darkened outlines around mineral clusters.
- Enderium palette: outline `#063D33`, dark `#0E6B5C`, mid `#17A184`, light `#45D6B2`, sparkle `#C9F7E8`, ender-purple accent `#8A5CF5`.
- Attach screenshots to any PR that changes visuals.

## Pull requests

- Topic branch off `main`, small focused scope.
- Say what changes **in-game**, not just in code.
- Before opening: `./gradlew build` green, `runServer` boots without data errors, and you have looked at your change in `runClient`.
- Big swings (new mechanics, biome changes, anything roadmap-shaped) start as an issue first so direction gets agreed before code gets written. Final creative direction stays with the project owner.
