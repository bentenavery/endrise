# Gallery shot list

A staged world named **Endrise Photo Studio** lives in the dev client's saves
(`run/saves/`, main branch). Open it with `./gradlew runClient`. You load in
creative, in the End, on the studio island, wearing the full kit: trimmed
netherite armor, infused soulbound pick in hand. Signs mark each shot.
Screenshots land in `run/screenshots/` (F2). Press F1 to hide the HUD first.

## Shot 1: the ore seam

You spawn facing it: an enderium seam drifting diagonally through the cliff
face, smithing template in an item frame beside it. Fly back a few blocks,
frame the seam with void sky at the edges, F1, F2. A second angle mid-mining
(survival, so the break particles show) is a nice bonus.

## Shot 2: the kit

By the chest (which holds a backup of everything). F5 to third person, face
the camera toward the armor trim shimmer, pick in hand. For a tooltip shot:
open the inventory and hover the pick so "Soulbound I / Efficiency V /
Unbreaking III" shows. Two flavors, pick the better one.

## Shot 3: the return

The one that sells the mod. At the SHOT 3 sign (the plank over the void):

1. `/execute in minecraft:the_end run spawnpoint @s 501 65 502`
2. `/gamemode survival`
3. Walk off the plank. Immediate-respawn is on; you reappear on the island.
4. Count 8 seconds, camera ready near your spawn point: portal particles
   burst + ender sound as the gear lands back in your slots. F2 on the burst.

Retakes are free: repeat 2-3. If the particle timing keeps beating you,
stage it: `/particle minecraft:portal 501 66 502 0.4 0.8 0.4 0.3 200`
(same particle the mechanic uses).

## If the world loads oddly

The player was injected into the save headlessly. If you spawn in the
overworld instead of on the island, run:
`/execute in minecraft:the_end run tp @s 501 65 502` and grab the kit from
the chest at the SHOT 2 sign. Everything else is unaffected.

## Upload

Modrinth project → Gallery → the three keepers from `run/screenshots/`,
one caption line each. Feature the Shot 3 frame.
