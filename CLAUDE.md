# Product Engineer — Discord Presence

You are the **Product Engineer** for the Discord Presence Minecraft mod. Ship features
end-to-end through the plan → test → merge gates with human oversight at each.

## Project Overview

- **Project:** Discord Presence — a generic NeoForge server mod that posts a Discord
  notification when a player joins, with an online reaction (removed on logout) and a death
  reaction. Foundation for future two-way Discord ↔ in-game chat.
- **Mod Loader:** NeoForge 1.21.1 (`neo_version` in `gradle.properties`)
- **Repo:** bh679/discordpresence-mc
- **Bundled into:** Dungeon Train (github.com/bh679/dungeon-train-mc) via NeoForge jarJar. The
  release asset MUST be `discordpresence-neoforge-<v>.jar` (`base.archivesName` in build.gradle)
  so DT's Ivy `patternLayout` (`[module]-neoforge-[revision].jar`) resolves the GitHub-release
  asset. Do not rename it.

## Standards

Follows `bh679/claude-templates`: rules auto-loaded via `~/.claude/rules/`
(development-workflow, git, versioning, coding-style, security); gate playbooks in
`.claude/gates/`.

## Key Rules

- Plan mode for all three gates; never merge without Gate 3 approval. Gates apply to ALL changes.
- SemVer in `gradle.properties` `mod_version`: PATCH per dev commit, MINOR on Gate 3 merge.
- **Secrets** (webhook URL, bot token) live only in the runtime SERVER config — NEVER commit them.
  `*-server.toml` is gitignored.

## Build & Test

```bash
./gradlew build        # -> build/libs/discordpresence-neoforge-<version>.jar
./gradlew test         # JUnit (pure logic)
./gradlew runServer    # dev dedicated server
./gradlew runClient    # dev client (single-player integrated server)
```

## Releasing (post-Gate 3)

Dispatch-only — the workflow creates the tag (never `git tag` manually):

```bash
gh workflow run release.yml -f tag=v<version>   # must match gradle.properties mod_version
```

The release uploads `discordpresence-neoforge-<version>.jar` to GitHub Releases — what Dungeon
Train's jarJar bundle fetches. After releasing, bump `discordpresence_version` in DT's
`gradle.properties` (+ the `build.gradle` jarJar pin and the mods.toml dependency range) to pull
the new build into DT.

## Architecture

- `discord/` — `DiscordHttp` (shared daemon executor + HttpClient), `DiscordWebhookClient`
  (post via webhook with `?wait=true`), `DiscordBotClient` (reactions via bot REST — **the
  future gateway / two-way read path lives here**), `DiscordService` (singleton facade + per-UUID
  message-future map; the seam two-way inbound routing extends), `DiscordMessageRef`.
- `event/DiscordPresenceEvents` — `@EventBusSubscriber` wiring vanilla player login/logout/death
  + server-stopping. Logic-free; not Dist-gated (runs on dedicated servers).
- `config/DiscordPresenceConfig` — SERVER config (secrets + message template + emojis).

Discord is **best-effort**: every failure is logged and swallowed; gameplay never blocks on HTTP.
All Discord I/O runs off-thread on the daemon executor.
