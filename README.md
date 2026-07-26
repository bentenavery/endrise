# Endrise

The End update, one tide at a time. A NeoForge mod for Minecraft 26.1.2.

Current content:

- **Enderium Ore**: spawns in end stone across every End biome (veins of ~4, five tries per chunk, y16-72). Needs a diamond pickaxe. Drops raw enderium (Fortune works, Silk Touch gives the block).
- **Raw Enderium → Enderium Ingot** via smelting or blasting.

## Dev

- `./gradlew runClient` starts a dev client with the mod loaded.
- `./gradlew build` produces the jar in `build/libs/`.
- Gradle wrapper is 9.5.1 (bumped from the MDK's 9.2.1, which cannot run on JDK 26). Compile toolchain is Java 25, auto-provisioned.
- Worldgen is data-driven: `src/main/resources/data/endrise/worldgen/` + the NeoForge biome modifier in `data/endrise/neoforge/biome_modifier/`.
