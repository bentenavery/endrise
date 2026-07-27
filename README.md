# Endrise

**The End update, one tide at a time.**

Endrise is a NeoForge mod for Minecraft 26.1.2 that grows the End into a place worth staying. It starts where every good expansion starts, with something buried in the ground, and builds outward: materials, then gear, then blocks, then the islands themselves.

![Enderium ore, raw enderium, and enderium ingot](docs/textures-preview.png)

## In the tide so far (v0.1.0)

- **Enderium Ore** generates in end stone across every End biome (veins of 4, five per chunk, y16-72). Diamond pickaxe required. Drops Raw Enderium; Fortune scales it, Silk Touch takes the block.
- **Raw Enderium** smelts or blasts into **Enderium Ingots**.

## Playing it

No public release yet. Build from source:

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

## Contributing

Endrise is open to contributors. The plan lives in [ROADMAP.md](ROADMAP.md), the how lives in [CONTRIBUTING.md](CONTRIBUTING.md), and the dev loop is one command:

```bash
./gradlew runClient
```

## License

[MIT](LICENSE).
