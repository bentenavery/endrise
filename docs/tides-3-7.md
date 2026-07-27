# The Covenant of Return — detailed designs for Tides 3-7

Working spec distilled from a multi-lens design review (game feel, community prior art, technical feasibility, narrative identity, progression), including an adversarial critique pass. Budget: 6 weekend-units across five shippable tides, every feature on both branches (main = 26.x dev tip, `1.21.1` = LTS).

**The rule that governs everything:** Soulbound owns death; infusion owns every other loss. Every return uses the same signature: ender chime, reverse-portal particles, 8-second delay.

---

## Tide 3 (v0.3) — The Void Gives Back — 1 unit

**Pitch.** Kick your infused pickaxe off an island edge; 8 seconds later the chime you learned to trust brings it home. A plain diamond pick dropped beside it simply dies. Marked items also refuse the 5-minute despawn timer and survive lava, but death drops stay Soulbound's exclusive business.

**Deliverables**
- Void capture: intercept ItemEntity removal below the kill plane via `EntityLeaveLevelEvent`, filtered to RemovalReason KILLED/DISCARDED only (never UNLOADED_TO_CHUNK, that's the dupe bug). Qualifies: infusion marker, enderium trim, Soulbound enchant, or new `#endrise:void_returning` tag (raw/ingot/block items, template).
- Death-drop exclusion: stamp every ItemEntity spawned during player drops with an `endrise:death_drop` flag at spawn time (not a thrower-alive check, which fast respawns defeat). All capture paths skip flagged entities. The rule, stated in the changelog: *dropped or lost comes back; died-with is Soulbound's job.*
- Despawn rescue: age-despawn of marked items routes through the return path at any Y, guarded by stack-not-empty (kills the pickup/merge dupe class) and the death-drop flag.
- Fire immunity on infused items (`fire_resistant` on 1.21.1, `damage_resistant` + fire tag on 26.x). Scope valve: may slip to Tide 4.
- Return path reuses SoulboundStore verbatim (8s, thrower's inventory, chime + particles, restart-safe). Ownerless items (hopper/dispenser) die vanilla-style.
- **Anvil hardening (pulled forward, fixes shipped bug):** `SoulboundEvents.onAnvilUpdate` currently only checks STORED_ENCHANTMENTS (books); item-to-item combining transfers Soulbound freely. Also inspect the sacrifice's live ENCHANTMENTS and cancel onto non-infused gear. Must ship before cenotaph loot exists.
- Ender pearl impact damage negated while wearing ≥1 **infused** armor piece (trim stays cosmetic + soulbound-eligibility; the template is the mechanical tier).
- "Returns from the void" teal tooltip; advancement **Nothing Is Lost** on first void return.

**Port notes.** Cheapest port: rides the v0.2 plumbing. Events have the same shape on both. Fire immunity forks by name. Pearl negation forks: 26.x has a dedicated ender_pearl damage type; on 1.21.1 pearls deal fall-type damage, so intercept `ProjectileImpactEvent` (owner == victim) or real fall damage gets zeroed.

**Risks.** Dupes are the whole risk class: capture-vs-despawn races, empty-stack guard, flag survival from spawn to kill plane. Test the Soulbound monopoly hardest.

**Verification.** Throw infused pick + trimmed helmet + ingot stack + plain pick into the void (three return, one dies). Ground despawn returns; lava float survives. Die on land with infused-not-soulbound gear: vanilla drops, despawn does NOT return them. Void death with infused-not-soulbound gear + fast respawn: items die. Anvil: tool+tool refused, book+infused works. Spam-Q dupe hunt; restart mid-return; hopper-fed void drop dies. Pearl-slam infused (0 dmg) vs trim-only (vanilla). Dedicated server: right items to right players.

---

## Tide 4 (v0.4) — Set in Stone — 1 unit

**Pitch.** The End gets a way to mourn and builders get a reason to stay: Polished End Stone, End Stone Tiles, the Mourner's Relief chiseled tile (the mod's recurring grave motif), and the Enderium Lantern whose teal embers drift *upward*. The Enderium Block lands as beacon base and 9-ingot sink. Least flashy tide on purpose: it's the material palette the Cenotaphs are built from.

**Deliverables**
- Enderium Block (9 ingots, reversible, diamond pick, `beacon_base_blocks`) + Raw Enderium Block.
- Deco family, all stonecutter-enabled: Polished End Stone + stairs/slab, End Stone Tiles + stairs/slab, Chiseled End Stone Tiles w/ Mourner's Relief (painted teal inlay, no emissive code). ~9 blocks. Walls only if the weekend has room (first cut).
- Enderium Lantern: vanilla LanternBlock reuse, light 15, recipe 1 raw + 4 end stone bricks → 2, upward teal flame via `animateTick` + vanilla particles.
- Full datagen pass on BOTH branches from shared source lists (blockstates, models, loot, recipes, tags, recipe-book triggers, tab ordering).
- README hero screenshot of an End porch.

**Port notes.** ~90% datagen; item model definitions live in different places per branch (the ore demonstrates both layouts); run each branch's datagen, don't copy JSON across. Port it the same weekend; no contributors exist before a public release.

**Risks.** Art volume (~12 textures) holding the palette without mush; curate ruthlessly. Never cut the lantern or the Relief (they carry the theme). Iterate the Relief on an in-game wall.

**Verification.** Place everything, stair corners, stonecut all paths, survival-break loot, beacon on 3x3 base, 81→9→81 round trip, particles drift upward, recipe book unlocks. Build the porch and stare: if it doesn't read "End" instantly, iterate.

---

## Tide 5 (v0.5) — Mourning Blooms, and the First Jar Leaves Home — 1 unit

**Pitch.** The world keeps score of loss: dying in the End sprouts a Mourning Bloom (teal stem, drooping purple-black head, light 7) on the nearest end stone. Rare natural patches exist so explorers meet them first. One bloom → two Void Petals; petal + bottle → **Draught of Return**: drink at camp, explore for 90 seconds, snap back to where you drank (fizzles with a chime if you left the End). And this is the weekend Endrise quietly goes public: Modrinth **alpha** channel, so real players tune the numbers.

**Deliverables**
- Mourning Bloom: cross-model flower, light 7, end-stone-only planting, bonemeal duplication, potted variant; crafts 2 Void Petals.
- Death-site hook: bloom on nearest valid end stone within 4 blocks of an End death (skip silently if none).
- Worldgen: patches of 3-7, ~1 per 6 chunks, all End biomes except the dragon island, via the ore's biome-modifier pattern.
- Draught of Return: shapeless petal + bottle (no brewing stand, deliberately), bottle returned; 90s effect stores drink position in a player attachment; on NATURAL expiry teleport if in the End, else fizzle (one dimension check); milk/death cancel cleanly; attachment persists logout, fires on next login expiry.
- Modrinth alpha release (staged; Avery presses publish): listing copy, icon, 3 screenshots, clean-instance smoke tests, README "help tune these numbers" section.
- Advancement **There and Back**.

**Port notes.** Flower + worldgen port cleanly via per-branch datagen. Consumable forks: data components (26.x) vs FoodProperties (1.21.1), ~10 lines each. Crafting-not-brewing dodges the brewing event fork. `MobEffectEvent.Expired` vs `.Remove` exists on both; attachments exist on both.

**Risks.** Teleport-on-expiry edges: logout mid-effect, obstructed return spot (pearl-style nudge, max 5), death mid-effect must never teleport. Density is the feel risk: constellations, not meadows. Going public invites mod-soup crash reports one tide early: that's the point.

**Verification.** Die → bloom appears, survives restart; count patches over 500 fresh blocks; bonemeal + pot; craft, drink, walk 100+, snap back with bottle; leave End + expire → fizzle only; milk cancels; die mid-effect → respawn untouched. Clean-instance installs both versions. Server: two deaths = two blooms; A's draught never moves B.

---

## Tide 6 (v0.6) — The Cenotaphs — 2 units

**Pitch.** Past the void gaps: a small ruin. Polished walls, the Mourner's Relief, blooms at the base, a lantern still burning over a grave slab. In the chest, always, one worn named piece of gear: "Marren's Pick", "What Isla Carried", half its durability gone, and on some of them the Soulbound covenant still humming. Someone came here before you, and not all of them bound their gear in time, which is exactly why it stayed. Zero dialogue. Renewable (not farmable) source of upgrade templates.

**Deliverables**
- Jigsaw structure `endrise:cenotaph`: 4 template variants (9x9 to 12x12) from the v0.4 family + purpur + blooms + 1 Enderium Block + grave slab + 1 chest; single start pool now; no mobs, silence is the design.
- Structure set: end_midlands, end_highlands, small_end_islands (never the dragon island), spacing 34 / separation 12, terrain-adapting surface placement.
- Loot: guaranteed 1 named lost-traveler item (iron/diamond, 30-70% durability used, 12-name pool via set_name), Soulbound via loot function on 40% (non-transferable thanks to Tide 3's anvil fix); 2-4 Void Petals; 2-5 raw enderium; 15% one upgrade template.
- Advancement **Someone Was Here** on entry; alpha release with a spacing-feedback ask.

**Port notes.** Author NBT templates on the **1.21.1 branch first**, then copy forward (DFU upgrades old NBT on load; never backward). Template palette only uses blocks identical on both. structure/structure_set/template_pool JSON is close but per-branch. Loot enchant-function shapes changed after 1.21.1: per-branch JSON. Budget weekend two for placement iteration, not porting.

**Risks.** End heightmap placement over void gaps (test 15+ natural spawns; add solid-ground condition if >1 in 5 look broken). Soulbound-in-loot works because the v0.2 restriction is anvil-side: test explicitly. Fallback: plain single-piece structure, nothing player-visible lost.

**Verification.** /locate from 5 points; visit 15 spawns; 10-chest loot tally against rates. The tide's key integration test: die with a looted soulbound item (v0.2 return fires: the full circle), then try to anvil its Soulbound onto plain gear (Tide 3 hardening refuses). Server: no gen-time stutter beyond vanilla (spark), different names for different players.

---

## Tide 7 (v0.7) — The Way Home — 1 unit

**Pitch.** After your gear, your drops, and everyone else's graves, it's your turn. The **Homeward Pearl** (ender pearl center, 4 Void Petals cardinal, 4 ingots corners): use anywhere, any dimension, arrive at your respawn point, consumed, chime on both ends. Enderium ore and blocks shed slow teal motes with a rare hum, so veins glimmer before you see them. An advancement tab turns the five tides into a guided tour, and the alpha listing gets promoted to full release with code alpha players already beat on.

**Deliverables**
- Homeward Pearl: delegates to each version's own vanilla respawn resolution (never reimplement bed-validity/anchor logic), world-spawn fallback, 5s cooldown, no-credits-screen check when leaving the End.
- Ambience: `animateTick` reverse-portal motes (~1 in 10 client ticks) + rare vanilla ambient sound on ore/block. Zero new audio assets, zero renderers.
- Advancement tab (8 nodes, pure JSON): Buried Light, Deeper Than Diamonds, Bound, Nothing Is Lost, There and Back, Someone Was Here, The Way Home, + a build node. All grants via `player.getAdvancements().award(...)` from existing handlers; zero custom criteria.
- Release promotion kit: README around the arc, hero GIF + 6 screenshots, CurseForge twin listing, both jars to alpha for a one-week soak, then channel promotion. Publishing is Avery's button.

**Port notes.** Respawn/teleport API names differ per branch (kept small by delegating to vanilla); advancement JSON drifts slightly; `animateTick` identical.

**Risks.** Cross-dimension edges shrink to: destroyed bed → world spawn without crashing; leaving the End without credits. If the weekend compresses: pearl + ambience + advancements ship to alpha, promotion slips a week, never the reverse.

**Verification.** Pearl from the End with a bed (no credits screen), with destroyed bed (world spawn), two players simultaneously on a server. Ore particles with no log spam; headless server boots clean. Full 8-advancement survival playthrough per branch. Clean-instance installs. One week alpha soak, zero blockers, friend plays 30 minutes, then promote.
