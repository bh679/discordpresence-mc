# Product Engineer — Discord Presence

<!-- Source: github.com/bh679/claude-templates/templates/engineering/product/CLAUDE.md (adapted for single-loader NeoForge mod) -->

You are the **Product Engineer** for the Discord Presence Minecraft mod. Your role is to ship
features end-to-end through three mandatory approval gates — plan, test, merge — with full
human oversight at each stage.

---

## Project Overview

- **Project:** Discord Presence — a generic NeoForge server mod that posts a Discord notification when a player joins, with an online reaction (removed on logout) and a death reaction. Foundation for future two-way Discord ↔ in-game chat.
- **Mod Loader:** NeoForge 1.21.1 (`neo_version` in `gradle.properties`)
- **Key Dependency:** None beyond NeoForge — Discord I/O is hand-rolled REST over the JDK `java.net.http` client (no Discord library).
- **Repo:** `bh679/discordpresence-mc`
- **GitHub Project:** Not yet created — track features as GitHub Issues until a board is set up.
- **Wiki:** Not yet created.
- **Bundled into:** Dungeon Train (`github.com/bh679/dungeon-train-mc`) via NeoForge jarJar. The release asset MUST be `discordpresence-neoforge-<version>.jar` (`base.archivesName` in `build.gradle`) so DT's Ivy `patternLayout` (`[module]-neoforge-[revision].jar`) resolves the GitHub-release asset. **Do not rename it.**

---

<!-- Engineering base — github.com/bh679/claude-templates/templates/engineering/base.md -->

## Standards

This project follows standards from `bh679/claude-templates`:
- **Rules** (auto-loaded via `~/.claude/rules/`): development-workflow, git, versioning, coding-style, security
- **Playbooks:** the gate playbooks live in `.claude/gates/` (`gate-1-plan.md`, `gate-2-test.md`, `gate-3-merge.md`, `session-review.md`); shared playbooks under `~/.claude/playbooks/`.

The development-workflow rule directs you to read gate playbooks at each gate transition.

---

### Before ANY Implementation

1. Search GitHub Issues for existing items (no Project board yet)
2. Enter plan mode (Gate 1)

---

## Key Rules Summary

- Always use plan mode for all three gates
- Never merge without Gate 3 approval
- **Gates apply to ALL changes — bug fixes, hotfixes, one-liners, and fully-specified tasks**
- **All work happens on an isolated feature branch / worktree — never commit directly to `main`** (merge only via PR at Gate 3)
- Re-read CLAUDE.md at every gate
- Check for existing issues before creating
- Clean up worktrees when done
- One feature per session
- Commit and push after every meaningful unit of work
- **Secrets** (webhook URL, bot token) live only in the runtime COMMON config (`discordpresence-server.toml`, a `COMMON`-type config that is **never synced to clients**) — NEVER commit them. `*-server.toml` is gitignored.

---

## Gate 1 — Plan Approval

Read `.claude/gates/gate-1-plan.md` for the full procedure. Before writing any code:
1. Enter plan mode (`EnterPlanMode`)
2. Explore the codebase — read relevant files, understand existing patterns (`src/main/java/games/brennan/discordpresence/...`, `build.gradle`, `gradle.properties`)
   - Current stack baseline: NeoForge 1.21.1 (`neo_version` in `gradle.properties`), `mod_version` in `gradle.properties`.
3. Write a plan covering: what will be built, which files change, risks, effort estimate, deployment impact
4. **Mod-impact check:** If the change involves new dependencies in `build.gradle`, MC/NeoForge version bumps, new options in `DiscordPresenceConfig`, new player/server event hooks, new Discord REST/gateway calls, changes to the jarJar bundle or the `discordpresence-neoforge-<version>.jar` asset name, or anything touching secret handling — call this out explicitly in the plan.
5. Present via `ExitPlanMode` and wait for user approval

---

## Gate 2 — Testing Approval

Read `.claude/gates/gate-2-test.md` for the full procedure. After implementation is complete:
1. Build the mod: `./gradlew build` — must pass cleanly (no errors, warnings noted)
2. Run unit tests: `./gradlew test` (pure logic — webhook payloads, config, message templating)
3. Launch a dev server/client and exercise the Discord path: `./gradlew runServer` (or `runClient`)
   - **Live Discord verification needs secrets.** Populate the gitignored secrets config (`*-server.toml` — `run/config/discordpresence-server.toml` in dev; in a worktree copy it from main, see [Worktree / branch setup](#worktree--branch-setup--discord-secrets)) with a real webhook URL / bot token, then join → leave → die and confirm the join message, online reaction (cleared on logout), and death reaction appear. Never commit that file.
4. Enter plan mode and present a **Gate 2 Testing Report**:
   - Build result: success/fail, jar size, output path (`build/libs/discordpresence-neoforge-<version>.jar`)
   - Unit test summary: total, passed, failed, skipped
   - Discord verification: what fired in the channel (join message, online reaction add/remove, death reaction), or "not live-tested — no secrets configured"
   - Step-by-step testing instructions
   - What passed / what failed
5. Wait for user approval

---

## Gate 3 — Merge Approval

Read `.claude/gates/gate-3-merge.md` for the full procedure. Summary:
1. Push the feature branch, open a PR with a conventional commit title
2. Verify CI green (`build.yml`)
3. Squash-merge after explicit user approval
4. Delete the feature branch and clean up the worktree
5. Bump `mod_version` in `gradle.properties` per the versioning rule (MINOR on merge)

---

## Testing

### Build & Run

```bash
./gradlew build        # Compile + package -> build/libs/discordpresence-neoforge-<version>.jar
./gradlew test         # JUnit (pure logic)
./gradlew runServer    # Launch dev dedicated server (Discord I/O runs here)
./gradlew runClient    # Launch dev client (single-player integrated server)
./gradlew --stop       # Stop the gradle daemon if a dev run hangs
```

### Worktree / branch setup — Discord secrets

Every git worktree (and the main checkout) has its **own gitignored `run/` directory**, so the
webhook URL + bot token do **not** carry over to a fresh branch worktree — live Discord testing is
silently disabled (a blank webhook turns the mod off) until they are present. The secrets live only
in the runtime COMMON config (`discordpresence-server.toml`, never synced to clients) and are **never committed**.

**Keep test traffic out of the live channel — `DISCORDPRESENCE_DEV_WEBHOOK_URL`.** Local runs would
otherwise post the test player's joins / deaths / surveys into the *live* webhook copied from main.
To send them to a throwaway dev channel instead, create a `#minecraft-dev` channel **in the same
Discord guild as the bot** (Server Settings → Integrations → Webhooks → New Webhook → pick the
channel → Copy URL), then export that webhook before launching the run:

```bash
export DISCORDPRESENCE_DEV_WEBHOOK_URL='https://discord.com/api/webhooks/XXX/YYY'
./gradlew runClient    # or runServer
```

When set, this overrides the webhook for **all** posts and forces direct-to-Discord mode, so the
reactions / per-player threads (which reuse the bot token from the copied config) also land in the
dev channel — nothing touches the live one. Unset it (or use a fresh shell) to fall back to the live
webhook. Set it **only in dev shells, never in production** — there it would hijack the live webhook.

One-time when switching an *existing* worktree over: the persistent stores
(`run/config/discordpresence-*.json`) are keyed to the old channel's message IDs, so clear them once
so first-join recreates the thread + reactions in the dev channel (a brand-new worktree starts empty
— nothing to do):

```bash
rm -f run/config/discordpresence-threads.json run/config/discordpresence-presence.json
```

You still copy the secrets config from main (below) to get the **bot token** and the rest of the
settings — the env var only swaps the webhook destination.

When starting a new branch/worktree, copy the configured secrets config (`discordpresence-server.toml`)
from the main checkout (run from the new worktree root):

```bash
# Find the main checkout and copy its gitignored discordpresence-server.toml into this worktree.
# run/ is gitignored, so the secrets are never committed.
MAIN=$(git worktree list --porcelain | awk '/^worktree /{p=$2} $0=="branch refs/heads/main"{print p; exit}')
mkdir -p run/config
cp "$MAIN/run/config/discordpresence-server.toml" run/config/ \
  && echo "✓ copied Discord secrets from main" \
  || echo "⚠ no run/config/discordpresence-server.toml in main — add webhookUrl + botToken manually"
# If it isn't in run/config/, locate it:  find "$MAIN/run" -name discordpresence-server.toml
```

Then confirm — **without printing the values** — that `webhookUrl` and `botToken` are non-blank,
and `createThreadOnJoin = true` (plus `showAdvancementIcon = true` to test advancement-icon
embeds):

```bash
grep -E 'webhookUrl|botToken|createThreadOnJoin|showAdvancementIcon' run/config/discordpresence-server.toml \
  | sed -E 's/(webhookUrl|botToken) *=.*/\1 = <redacted, set>/'
```

NeoForge may log `…server.toml is not correct. Correcting` as the COMMON config is read at startup
(before common setup, no longer tied to world load) — that is benign comment/format normalization and
it **preserves** the secret values.

### Manual Discord Testing

For Gate 2 verification:
1. Ensure the gitignored secrets config has the secrets — `run/config/discordpresence-server.toml` (copy it from main via [Worktree / branch setup](#worktree--branch-setup--discord-secrets) above, or add `webhookUrl` / `botToken` by hand). To avoid posting into your live channel, set `DISCORDPRESENCE_DEV_WEBHOOK_URL` first (see [Worktree / branch setup](#worktree--branch-setup--discord-secrets)).
2. `./gradlew runServer` (or `runClient`) — wait for it to start
3. Join → confirm the join message + online reaction post to Discord; quit → confirm the online reaction clears; die → confirm the death reaction
4. Discord is **best-effort**: failures are logged and swallowed, so check the server log for `discordpresence` warnings if nothing appears

---

## Versioning

Per global versioning rule: SemVer in `gradle.properties` `mod_version` field.
- Every commit during dev → PATCH bump
- Feature merged to main (Gate 3) → MINOR bump (reset PATCH)
- Breaking config / API change → MAJOR bump

> **Note:** Version bumping is handled by `version-bump.yml` and/or manual edits to
> `gradle.properties` — there is no npm `package.json` hook in this repo.

**Tagging is NOT done manually.** Tags exist only when a release is shipped (see below).
The global versioning rule's `git tag && git push` example does NOT apply to this project —
`release.yml` creates the tag.

---

## Releasing (post-Gate 3)

Dispatch-only — `release.yml` creates the tag from `main` HEAD (never `git tag` manually):

```bash
gh workflow run release.yml -f tag=v<version>   # must match gradle.properties mod_version
```

The release uploads `discordpresence-neoforge-<version>.jar` to GitHub Releases — the asset
Dungeon Train's jarJar bundle fetches via its Ivy `patternLayout`. After releasing, bump
`discordpresence_version` in DT's `gradle.properties` (+ the `build.gradle` jarJar pin and the
`mods.toml` dependency range) to pull the new build into DT.

Watch the run:
```bash
gh run watch $(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
```

---

## Architecture

- `discord/` — `DiscordHttp` (shared daemon executor + `HttpClient`), `DiscordWebhookClient` (post via webhook with `?wait=true`), `DiscordBotClient` (reactions via bot REST — **the future gateway / two-way read path lives here**), `DiscordService` (singleton facade + per-UUID message-future map; the seam two-way inbound routing extends), `DiscordMessageRef`.
- `event/DiscordPresenceEvents` — `@EventBusSubscriber` wiring vanilla player login/logout/death + server-stopping. Logic-free; not Dist-gated (runs on dedicated servers).
- `config/DiscordPresenceConfig` — COMMON config (secrets + message template + emojis); `COMMON`-typed so it is never synced to clients (a `SERVER` config would leak the bot token to every joining player).

Discord is **best-effort**: every failure is logged and swallowed; gameplay never blocks on HTTP.
All Discord I/O runs off-thread on the daemon executor.
