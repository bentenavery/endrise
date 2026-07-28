# Endrise Roadmap

**The identity, one sentence:** in the End, nothing enderium is ever truly lost, and each kind of loss has exactly one answer.

Soulbound owns death. Enderium owns every other loss: the void, the despawn timer, fire. Every act of return shares one signature: the ender chime, reverse-portal particles, and the 8-second heartbeat.

Detailed designs for the upcoming tides (deliverables, risks, verification, per-version port notes) live in [docs/covenant-of-return.md](docs/covenant-of-return.md).

**Status:** all eight tides shipped. v1.0.0 is the release candidate, soaking in the alpha channel for a week before the listing is promoted to full release.

## Shipped

- [x] **Tide 1 (v0.1) — Enderium**: ore in end stone across all End biomes, raw → ingot smelting
- [x] **Tide 2 (v0.2, v0.2.1) — Soulbound**: the anvil-only Soulbound enchantment and gear that teleports back to its exact slots 8 seconds after you die; v0.2.1 closed the anvil item-to-item transfer hole
- [x] **Tide 3 (v0.3) — The Enderium Tier**: enderium gear became real items, the tier above netherite. Netherite gear + upgrade template + enderium ingot transmutes at the smithing table; enchantments and durability carry; full sprite set and worn armor. Soulbound is enderium-exclusive. (Replaced the invisible infusion-marker design after playtesting: an upgrade you can't see never reads as an upgrade.)
- [x] **Tide 4 (v0.4) — The Void Gives Back**: enderium items you drop return from the void, the despawn timer, and fire; death drops stay Soulbound's business; pearls cost nothing in enderium armor. Also fixed two shipped soulbound bugs found by adversarial review (death-screen delivery wipe, store save crash).

- [x] **Tide 5 (v0.5) — Set in Stone**: the End's masonry. Polished End Stone and End Stone Tiles families with stairs and slabs, Chiseled tiles bearing the Mourner's Relief, Blocks of Enderium (beacon base) and Raw Enderium, and the Enderium Lantern with upward-drifting teal embers. Full stonecutting graph, recipe-book wiring, and a generator that cribs every JSON format from the running version's own jar.

- [x] **Tide 6 (v0.6) — Mourning Blooms**: the world keeps score of loss. End deaths sprout Mourning Blooms; rare natural constellations grow in the outer biomes; blooms craft Void Petals and petals bottle into the Draught of Return (drink, wander 90 seconds, snap back, End-only, last drink re-anchors loudly). The petal economy is open.
- [x] **Tide 7 (v0.7) — The Cenotaphs**: four ruin variants (vigil, waygate, ring, fallen hall) scattered across the outer End on ground-checked placements that yield to the void: no floating graves, and /locate never lies. Every chest holds one named, worn remembrance (about 40% still Soulbound, and the covenant honors the mark on any metal; one traveler in twelve carried enderium), Void Petals, raw enderium, and a 15% upgrade template: the first renewable template source. Advancement: Someone Was Here.

- [x] **Tide 8 (v1.0) — The Way Home**: the Homeward Pearl (petals + ingots around an ender pearl) carries you from anywhere, any dimension, to your own respawn point, consumed, resolved by vanilla's own bed/anchor rules, and it can never roll the credits. Enderium ore and blocks glimmer with reverse-portal motes and a rare hum. The advancement tab completes its eight-node arc. The 1.0 candidate soaks in alpha for a week before the listing is promoted to full release.

## After 1.0

The arc is complete; what follows is expansion, not obligation. The strongest candidates, in rough order: the enderium respawn anchor (an End-native "set your home here" block), a neutral mob with real design behind it, and growing the cenotaph start pool with new ruin families. Fresh ideas welcome in issues.

## Deliberately rejected (so contributors don't re-propose them)

- ~~**Enderium tool/armor tier**: unanimous no. Infusion IS the enderium gear identity.~~ **Overturned in v0.3** after playtesting: an invisible component never read as an upgrade in hand. Enderium is now a first-class tier above netherite, and infusion-as-marker is the rejected idea. Kept here as a reminder that playtests outrank design docs.
- **A neutral mob**: the classic multi-weekend hobbyist stall (entity + AI + rendering on two branches). Needs real design first; revisit after 1.0.
- **Enderium respawn anchor**: right idea, one tide too early; the Homeward Pearl delivers "you come back" cheaply. Strongest post-1.0 candidate.
- **Custom ambient audio**: generated audio is the asset class most likely to embarrass the release; vanilla sounds carry the atmosphere.

## Good first contributions

- Playtest reports against the live tuning numbers: vein density, transmute costs, the Soulbound book recipe, the 8-second delay, bloom density and draught duration, and (from Tide 7 on) cenotaph spacing and loot rates
- Texture polish passes within the enderium palette (see CONTRIBUTING.md)
- Recipe advancement triggers and creative-tab ordering audits
