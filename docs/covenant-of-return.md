# The Covenant of Return — detailed designs for Tides 4-8

Working spec distilled from a multi-lens design review (game feel, community prior art, technical feasibility, narrative identity, progression) plus an adversarial critique pass, updated 2026-07-27 for the v0.3 enderium-tier redesign and the early Modrinth submission. Supersedes `tides-3-7.md`.

Budget: ~6 weekend-units across five shippable tides, every feature on both branches (main = 26.x dev tip, `1.21.1` = LTS).

**The rule that governs everything:** Soulbound owns death; enderium owns every other loss. Every return uses the same signature: ender chime, reverse-portal particles, 8-second delay.

**What the v0.3 pivot changed here:** "infused/trimmed gear" is gone; the qualifying set is simply the enderium items (`#endrise:soulbound_able`) plus enderium materials. Gear items already ship fire-resistant (netherite-style), so the fire work remaining is materials only. The public listing originally scheduled for the blooms tide already happened (submitted at v0.3.0), so that tide sheds its listing work.

---

## Tide 4 (v0.4) — The Void Gives Back — 1 unit

**Pitch.** Kick your enderium pickaxe off an island edge; 8 seconds later the chime you learned to trust brings it home. A netherite pick dropped beside it simply dies. Enderium also refuses the 5-minute despawn timer, and the materials float in lava like netherite. Death drops stay Soulbound's exclusive business.

**Deliverables**
- Void capture: intercept ItemEntity removal below the kill plane via `EntityLeaveLevelEvent`, filtered to RemovalReason KILLED/DISCARDED only (never UNLOADED_TO_CHUNK, that's the dupe bug). Qualifies: `#endrise:void_returning` = the enderium gear tag + raw enderium, ingot, ore/storage block items, upgrade template.
- Death-drop exclusion: stamp every ItemEntity spawned during player drops with an `endrise:death_drop` flag at spawn time (not a thrower-alive check, which fast respawns defeat). All capture paths skip flagged entities. The rule, stated in the changelog: *dropped or lost comes back; died-with is Soulbound's job.*
- Despawn rescue: age-despawn of marked items routes through the return path at any Y, guarded by stack-not-empty (kills the pickup/merge dupe class) and the death-drop flag.
- Fire/lava float for enderium materials (gear already has it from v0.3): `fire_resistant` property on 1.21.1, `damage_resistant` + fire tag on 26.x, applied to raw/ingot/template (block items follow their blocks).
- Return path reuses SoulboundStore verbatim (8s, thrower's inventory, chime + particles, restart-safe). Ownerless items (hopper/dispenser) die vanilla-style.
- Ender pearl impact damage negated while wearing ≥1 piece of enderium armor.
- "Returns from the void" teal tooltip on qualifying items; advancement **Nothing Is Lost** on first void return.

**Port notes.** Cheapest port: rides the v0.2 plumbing. `EntityLeaveLevelEvent`, RemovalReason, and player-drop events have the same shape on both branches; verify event-before-vanilla-discard ordering per branch first. Fire property forks by name. Pearl negation forks: 26.x has a dedicated ender_pearl damage type (zero it in the incoming-damage event); on 1.21.1 pearls deal fall-type damage, so intercept `ProjectileImpactEvent` (owner == victim) or real fall damage gets zeroed.

**Risks.** Dupes are the whole risk class: capture-vs-despawn races, the empty-stack guard, flag survival from spawn to kill plane. Test the Soulbound monopoly hardest: a fast respawner must never convert a void death into a free return.

**Verification.** Throw enderium pick + ingot stack + netherite pick into the void (two return, one dies). Ground despawn returns; lava-toss survives and floats. Die on land with enderium-but-not-soulbound gear: vanilla drops, and their eventual despawn does NOT return them. Void death with enderium-not-soulbound gear + fastest possible respawn: items die (monopoly held); with Soulbound: exactly one copy returns via the v0.2 path. Spam-Q dupe hunt; restart mid-return; hopper-fed void drop dies. Pearl-slam with one armor piece (0 damage) vs none (vanilla); on 1.21.1 confirm cliff-fall damage still applies. Dedicated server: right items to right players.

---

## Tide 5 (v0.5) — Set in Stone — 1 unit

**Pitch.** The End gets a way to mourn and builders get a reason to stay: Polished End Stone, End Stone Tiles, the Mourner's Relief chiseled tile (the mod's recurring grave motif), and the Enderium Lantern whose teal embers drift *upward*. The Enderium Block lands as beacon base and 9-ingot sink. Least flashy tide on purpose: it's the material palette the Cenotaphs are built from.

**Deliverables**
- Enderium Block (9 ingots, reversible, diamond-tier mining, `beacon_base_blocks`) + Raw Enderium Block.
- Deco family, all stonecutter-enabled: Polished End Stone + stairs/slab, End Stone Tiles + stairs/slab, Chiseled End Stone Tiles with the Mourner's Relief (painted teal inlay, no emissive code). ~9 blocks. Walls only if the weekend has room (first cut).
- Enderium Lantern: vanilla LanternBlock reuse (standing + hanging), light 15, recipe 1 raw + 4 end stone bricks → 2, upward teal flame via `animateTick` + vanilla particle types.
- Full datagen pass on BOTH branches from shared source lists (blockstates, models, loot, recipes, tags, recipe-book triggers, creative tab ordering).
- README + Modrinth gallery hero shot of an End porch built from the family.

**Port notes.** ~90% datagen; item model definitions live in different places per branch (the ore demonstrates both layouts); run each branch's datagen rather than copying JSON across. Port the same weekend.

**Risks.** Art volume (~12 textures) holding the palette without mush; batch-generate, curate ruthlessly. Never cut the lantern or the Relief (they carry the theme). Iterate the Relief on an in-game wall, not in the editor.

**Verification.** Place everything, stair corners, stonecut all paths, survival-break loot, beacon on a 3x3 enderium base, 81→9→81 compression round trip, particles drift upward standing and hanging, recipe book unlocks. Build the porch and stare: if it doesn't read "End" instantly, iterate before shipping.

---

## Tide 6 (v0.6) — Mourning Blooms — 1 unit

**Pitch.** The world keeps score of loss: dying in the End sprouts a Mourning Bloom (teal stem, drooping purple-black head, light 7) on the nearest end stone; your friend finds your death sites before you tell the story. Rare natural patches exist so explorers meet the flower before anyone dies. One bloom → two Void Petals; petal + bottle → the **Draught of Return**: drink at camp, explore for 90 seconds, snap back to where you drank. Fizzles with a chime if you left the End: one rule, easy to teach.

**Deliverables**
- Mourning Bloom: cross-model flower, light 7, end-stone-only planting, bonemeal duplication, potted variant; crafts into 2 Void Petals.
- Death-site hook: bloom on the nearest valid end stone within 4 blocks of an End death (skip silently if none).
- Worldgen: patches of 3-7, ~1 per 6 chunks, all End biomes except the dragon island, via the ore's biome-modifier pattern.
- Draught of Return: shapeless petal + bottle (no brewing stand, deliberately), bottle returned; 90s effect stores drink position in a player attachment; on NATURAL expiry teleport if in the End, else fizzle (one dimension check); milk/death cancel cleanly; attachment persists logout and fires on next login expiry.
- Ships through the live Modrinth listing with a loud changelog ask: bloom density and draught duration are first guesses, argue with them.
- Advancement **There and Back** on first draught return.

**Port notes.** Flower + worldgen port cleanly via per-branch datagen. Consumable forks: data components (26.x) vs FoodProperties (1.21.1), ~10 lines each. Crafting-not-brewing dodges the brewing event fork. `MobEffectEvent.Expired` vs `.Remove` exists on both; player attachments exist on both. Effect registration boilerplate differs slightly.

**Risks.** Teleport-on-expiry edges: logout mid-effect, obstructed return spot (pearl-style upward nudge, max 5 blocks), death mid-effect must never teleport the respawned player. Density is the feel risk: constellations, not meadows.

**Verification.** Die → bloom appears, survives restart; count natural patches over 500 fresh blocks; bonemeal + pot; craft, drink, walk 100+, snap back with bottle returned; leave the End + expire → fizzle only; milk cancels; die mid-effect → respawn untouched. Clean-instance installs on both versions. Server: two deaths = two blooms; A's draught never moves B.

---

## Tide 7 (v0.7) — The Cenotaphs — 2 units

**Pitch.** Past the void gaps: a small ruin. Polished walls, the Mourner's Relief, blooms at the base, a lantern still burning over a grave slab. In the chest, always, one worn named piece of gear: "Marren's Pick", "What Isla Carried", half its durability gone, sometimes still humming with Soulbound, and rarely enderium itself: someone made it that far. Zero dialogue, pure environmental storytelling, spaced so each find feels like a find, and the renewable (not farmable) source of upgrade templates.

**Deliverables**
- Jigsaw structure `endrise:cenotaph`: 4 template variants (9x9 to 12x12 footprints) built from the Tide 5 family + purpur + planted blooms + 1 Enderium Block core + grave slab + 1 chest; single start pool now so later tides can grow it; no mobs, silence is the design.
- Structure set: end_midlands, end_highlands, small_end_islands (never the dragon island), spacing 34 / separation 12 / dedicated salt, terrain-adapting surface placement.
- Loot table: guaranteed 1 named lost-traveler item (iron or diamond, 8% enderium, 30-70% durability used, 12-name pool via set_name with distinct gear pairings), Soulbound via loot function on 40% of them (safe: the anvil transfer hole was closed in v0.2.1); guaranteed 2-4 Void Petals and 2-5 raw enderium; 15% one Enderium Upgrade Smithing Template.
- Advancement **Someone Was Here** on entering the structure bounds; changelog asks for spacing feedback.

**Port notes.** Critical order: author the NBT templates on the **1.21.1 branch first**, then copy forward (DataFixerUpper upgrades old NBT on load, never backward). Template palette uses only blocks identical on both branches. structure/structure_set/template_pool JSON is close but per-branch (biome tags, placement fields, pack formats). Loot enchant-function shapes changed after 1.21.1: per-branch JSON; set_name and set_damage are stable. Budget weekend two for placement iteration, not porting.

**Risks.** End heightmap placement over void gaps (test 15+ natural spawns; add a solid-ground condition if more than 1 in 5 look broken). Fallback if jigsaw fights back: plain single-piece structure with 4 variants, nothing player-visible lost.

**Verification.** /locate from 5 random points; visit 15 natural spawns; 10-chest loot tally against rates. The tide's key integration test: die with a looted soulbound item and confirm the v0.2 return fires (the full circle), then try to anvil its Soulbound onto plain gear and confirm the v0.2.1 hardening refuses. Server: no gen-time stutter beyond vanilla baseline (spark tick report); different players loot different names.

---

## Tide 8 (v1.0) — The Way Home — 1 unit

**Pitch.** After your gear, your drops, and everyone else's graves, it's your turn. The **Homeward Pearl** (ender pearl center, 4 Void Petals cardinal, 4 enderium ingots corners): use anywhere, any dimension, arrive at your respawn point, consumed, chime on both ends. Enderium ore and blocks shed slow teal reverse-portal motes with a rare ambient hum, so a vein glimmers before you see the texture. An advancement tab turns the whole arc into a guided tour, and the listing gets promoted from alpha to the full 1.0 release with code alpha players already beat on.

**Deliverables**
- Homeward Pearl: delegates to each version's own vanilla respawn resolution (never reimplement bed-validity/anchor logic), world-spawn fallback, consumed, 5s cooldown, ender kit on both ends, explicit no-credits-screen check when leaving the End.
- Ambience via `animateTick` on Enderium Ore and Enderium Block: reverse-portal motes (~1 in 10 client ticks) plus a rare vanilla ambient sound. Zero new audio assets, zero renderers.
- Advancement tab "Endrise" (8 nodes, pure JSON display): Buried Light, Deeper Than Diamonds, Bound, The Tier Above, Nothing Is Lost, There and Back, Someone Was Here, The Way Home. All grants via `player.getAdvancements().award(...)` from existing handlers; zero custom criteria.
- Release promotion: README and listing rewritten around the five-tide arc, hero GIF + gallery, changelog, one-week alpha soak of the 1.0 candidate, then channel promotion. Publishing and promotion are Avery's buttons.

**Port notes.** Respawn/teleport API names differ per branch (kept small by delegating to vanilla); advancement JSON drifts slightly; `animateTick` identical. Release builds are each branch's existing gradle; test jars in fresh launcher instances, never the dev workspace.

**Risks.** Cross-dimension edges shrink to: destroyed bed → world spawn without crashing; leaving the End without the credits screen. If the weekend compresses: pearl + ambience + advancements ship to alpha and the promotion slips a week, never the reverse.

**Verification.** Pearl from the End with a bed set (arrive, no credits), destroyed bed (world spawn, no crash), two players simultaneously on a server. Ore particles with no log spam; headless server boots clean. Full 8-advancement survival playthrough per branch; clean-instance installs; one week of alpha soak with zero blockers, friend plays 30 minutes, then promote.
