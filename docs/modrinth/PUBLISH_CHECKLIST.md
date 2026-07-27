# Modrinth publish checklist

One-time setup, ~10 minutes. After this, every GitHub release auto-publishes.

## 1. Create the project (browser, ~5 min)

- modrinth.com → Create a project
  - Slug: `endrise` (checked free on 2026-07-27)
  - Name: **Endrise**
  - Summary: `A slow-building End update: enderium, infusion, and gear that comes back from death.`
  - Description: paste `docs/modrinth/BODY.md`
  - Icon: upload `docs/modrinth/icon.png`
  - License: MIT
  - Environment: client **and** server required
  - Categories: adventure, equipment, game-mechanics
  - Source/Issues links: `https://github.com/bentenavery/endrise` (+ `/issues`)
- Submit for review can wait until a version is uploaded (the workflow below does that).

## 2. Wire the token (terminal, ~2 min)

- Modrinth → Settings → Personal Access Tokens → create one with the
  **Create versions** + **Write versions** scopes, any expiry you like.
- Then:

```bash
gh variable set MODRINTH_ID --repo bentenavery/endrise --body endrise
```

```bash
gh secret set MODRINTH_TOKEN --repo bentenavery/endrise
```

(the second command prompts; paste the token there, nowhere else)

## 3. Backfill the current release

```bash
gh workflow run publish.yml --repo bentenavery/endrise -f tag=v0.2.1
```

Creates two Modrinth versions on the alpha channel: `26.1.2-0.2.1` and `1.21.1-0.2.1`.
Every future `gh release create` publishes automatically; no manual step.

## 4. Gallery (the one thing Bilbert can't do headless)

Three F2 screenshots in-game, upload to the Modrinth gallery:
- enderium ore seam in end stone
- an infused/trimmed loadout screen
- the Soulbound return moment (portal particles)

## Later, optional: CurseForge

Same workflow handles it the moment these exist (numeric project id from the CF dashboard):

```bash
gh variable set CURSEFORGE_ID --repo bentenavery/endrise --body <numeric-id>
```

```bash
gh secret set CURSEFORGE_TOKEN --repo bentenavery/endrise
```
