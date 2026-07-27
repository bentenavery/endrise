# Endrise Roadmap

**The identity, one sentence:** in the End, nothing marked by enderium is ever truly lost, and each kind of loss has exactly one answer.

Soulbound owns death. Infusion owns every other loss (the void, the despawn timer, fire). Every act of return shares one signature: the ender chime, reverse-portal particles, and the 8-second heartbeat.

Detailed designs (deliverables, risks, verification, per-version port notes) live in [docs/tides-3-7.md](docs/tides-3-7.md).

## Shipped

- [x] **Tide 1 (v0.1) — Enderium**: ore in end stone across all End biomes, raw → ingot smelting
- [x] **Tide 2 (v0.2) — Soulbound**: enderium armor trims, tool infusion via smithing template, the Soulbound enchantment, and gear that teleports back to its exact slots 8 seconds after you die

## The Covenant of Return (Tides 3-7, ~6 weekends total)

- [ ] **Tide 3 (v0.3) — The Void Gives Back** *(1 weekend)*
  Marked items (infused, trimmed, soulbound, or enderium materials) climb back out of the void, refuse the 5-minute despawn timer, and survive fire. Death drops are explicitly excluded so Soulbound keeps its monopoly on death. Ships the anvil hardening fix, plus ender pearl impact damage negated while wearing infused armor.

- [ ] **Tide 4 (v0.4) — Set in Stone** *(1 weekend)*
  The End's masonry: Enderium Block (beacon base, 9-ingot sink), Polished End Stone + End Stone Tiles families with stairs/slabs, Chiseled tiles bearing the Mourner's Relief, and the Enderium Lantern with teal flame and upward-drifting embers. Pure data + datagen; this is the material palette the Cenotaphs are built from.

- [ ] **Tide 5 (v0.5) — Mourning Blooms** *(1 weekend)*
  A player death in the End sprouts a Mourning Bloom on the nearest end stone; rare natural patches too. Blooms craft into Void Petals; a petal + bottle makes the Draught of Return (drink, explore for 90 seconds, snap back to where you drank). Also the tide where Endrise goes public on Modrinth's alpha channel so real players tune the numbers.

- [ ] **Tide 6 (v0.6) — The Cenotaphs** *(2 weekends)*
  Small jigsaw ruins scattered across the outer End: memorial stonework, blooms, a burning lantern, and a chest holding one named, worn piece of gear from a traveler who never made it home, sometimes still soulbound. Environmental storytelling, zero dialogue, and the renewable source of upgrade templates.

- [ ] **Tide 7 (v0.7) — The Way Home** *(1 weekend)*
  The Homeward Pearl (petals + ingots around an ender pearl): from anywhere, any dimension, it carries you to your respawn point, consumed. Enderium ore and blocks get ambient teal motes and a rare hum. An advancement tab turns the arc into a guided tour, and the alpha listing is promoted to full release.

## Deliberately rejected (so contributors don't re-propose them)

- **Enderium tool/armor tier**: unanimous no. Infusion IS the enderium gear identity; a parallel tier is stat noise and permanent double maintenance.
- **A neutral mob**: the classic multi-weekend hobbyist stall (entity + AI + rendering on two branches). Needs real design first; revisit after v0.7.
- **Enderium respawn anchor**: right idea, one tide too early; the Homeward Pearl delivers "you come back" cheaply. Strongest v0.8 candidate.
- **Custom ambient audio**: generated audio is the asset class most likely to embarrass the release; vanilla sounds carry the atmosphere.

## Good first contributions

- Playtest reports against the tuning numbers (bloom density, cenotaph spacing, draught duration) once v0.5 is on the alpha channel
- Texture polish passes within the enderium palette (see CONTRIBUTING.md)
- Recipe advancement triggers and creative-tab ordering audits
