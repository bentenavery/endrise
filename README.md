# Endrise

**The End update, one tide at a time.**

Endrise is a NeoForge mod for Minecraft 26.1.2 that grows the End into a place worth staying. It starts where every good expansion starts, with something buried in the ground, and builds outward: materials, then gear, then blocks, then the islands themselves.

![Enderium ore, raw enderium, and enderium ingot](docs/textures-preview.png)

## In the tide so far (v0.2.1)

- **Enderium Ore** generates in end stone across every End biome (veins of 4, five per chunk, y16-72). Diamond pickaxe required. Drops Raw Enderium; Fortune scales it, Silk Touch takes the block.
- **Raw Enderium** smelts or blasts into **Enderium Ingots**.
- **Infusion**: the Enderium Upgrade Smithing Template (7 end stone + popped chorus fruit + ingot) infuses diamond and netherite tools in place; armor counts through enderium **trims** (any pattern).
- **Soulbound**: an anvil-only enchantment for infused/trimmed gear. Die, and eight seconds later the gear teleports back into the slots it left. Survives restarts, void deaths included.

## Playing it

Grab the jar matching your Minecraft version from [Releases](https://github.com/bentenavery/endrise/releases), drop it in the `mods/` folder of a NeoForge instance. Or build from source:

```bash
./gradlew build
```

The jar lands in `build/libs/` and goes in the `mods/` folder of any NeoForge 26.1.2 instance.

## Versions

| Branch | Minecraft | NeoForge |
|---|---|---|
| `main` (dev tip) | 26.1.2 | 26.1.2.87+ |
| `1.21.1` | 1.21.1 | 21.1.244+ |

New content lands on `main` first, then gets ported. Releases ship one jar per supported version.

## Help tune the numbers

Every number in the current tide is a first guess, not a verdict: vein size and density, the infusion recipe cost, the Soulbound book recipe, the 8-second return delay. If something feels too cheap, too grindy, or too safe, [open an issue](https://github.com/bentenavery/endrise/issues) and say what you did and what felt wrong. Real playtime beats theorycrafting.

## Contributing

Endrise is open to contributors. The plan lives in [ROADMAP.md](ROADMAP.md), the how lives in [CONTRIBUTING.md](CONTRIBUTING.md), and the dev loop is one command:

```bash
./gradlew runClient
```

## License

[MIT](LICENSE).
