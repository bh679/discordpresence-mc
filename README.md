# Discord Presence

A lightweight **NeoForge 1.21.1** server mod that posts a Discord message when a player
joins, then keeps it live with reactions:

- 📨 **On join** — posts a message to a Discord channel via a webhook. The first time a player
  joins, it opens a **persistent per-player thread** anchored to that message; later joins post
  into it.
- 🟢 **While online** — a bot adds an online reaction to that message…
- ⚪ **On logout** — …and removes it.
- 💀 **On death** — the bot adds a death reaction.
- 🏆 **On advancement** — earned advancements post into the player's thread as a coloured embed
  (filterable by namespace).
- 💬 **Two-way chat** — in-game chat relays to Discord under each player's name (into their
  thread), and Discord **replies or thread messages** relay back into the game.
- 🕯️ **Whispers into the darkness** — when a player chats with no active Discord conversation,
  the game shows a random grey flavour line (e.g. *"Steve whispers into the void, is anyone
  there?"*) — purely in-game, nudging Discord to reply. It turns off once a Discord reply lands
  and re-arms after a quiet spell.
- 🕓 **Last seen online** *(for bundling mods)* — track configured Discord users' presence and expose
  a `lastSeenOnline(userId)` / `isDiscordUserOnline(userId)` query, so a mod can render e.g. *"Brennan
  was last seen online 5 minutes ago"*. Needs the bot token + the **Presence** privileged intent.

It hooks only vanilla player events, so it works on any NeoForge server — and in singleplayer.
It's also bundled into [Dungeon Train](https://github.com/bh679/dungeon-train-mc).

## Why a webhook *and* a bot?

Discord **webhooks can post messages but cannot add reactions** — only a **bot token** can.
So Discord Presence uses the webhook to post and the bot to react, **create the per-player
thread**, and post advancement embeds. The same bot also opens a **gateway** (WebSocket)
connection to read Discord replies for two-way chat — something webhooks fundamentally cannot do.
All with zero external dependencies: just the JDK plus NeoForge's Gson.

## Setup

All settings live in `config/discordpresence-server.toml` (created on first server start).
The mod is **off until a webhook URL is set**.

### 1. Create a webhook
Server Settings → Integrations → Webhooks → New Webhook → pick a channel → **Copy Webhook URL**.
Put it in `webhookUrl`.

### 2. Create a bot (for the reactions)
1. <https://discord.com/developers/applications> → **New Application**.
2. **Bot** → **Reset Token** → copy it into `botToken`.
3. **OAuth2 → URL Generator** → scope `bot`, permissions **View Channel**, **Read Message
   History**, **Add Reactions**, **Create Public Threads**, **Send Messages in Threads** → open
   the URL → add the bot to the **same server** as the webhook's channel.

Reactions need no privileged intents. **Two-way chat does** — see below.

### 3. Enable two-way chat (optional)
1. **Developer Portal → your app → Bot → Privileged Gateway Intents** → turn on **MESSAGE
   CONTENT INTENT**. Without it, relayed Discord messages arrive blank (the mod logs a one-time
   warning and the gateway refuses to connect with close code `4014`).
2. Leave `relayDiscordToGame` / `relayGameToDiscord` on (the defaults).
3. **Singleplayer / LAN:** the first launch shows a one-time title-screen prompt to enable
   network features — choose **Enable**. Dedicated servers skip this (on by default). Change it
   anytime via `networkConsent` in `discordpresence-client.toml`.

Discord → game is **targeted**: a Discord message relays in-game only when it **replies to** a
message the mod posted for a player, or is posted in **that player's thread**. General channel
chatter is ignored, and the bot's/webhook's own messages are never echoed back.

### Config reference

| Key | Default | Meaning |
|---|---|---|
| `webhookUrl` | `""` | Webhook URL. Blank disables the mod. **Secret.** |
| `botToken` | `""` | Bot token for reactions. Blank → messages post without reactions. **Secret.** |
| `joinMessageTemplate` | `🎮 **{player}** started the game` | Returning-player join line (posted into their thread). `{player}` → player name. |
| `onlineEmoji` | `🟢` | Reaction added while online, removed on logout. |
| `deathEmoji` | `💀` | Reaction added on death. |
| `autoDeathReport` | `true` | Post a rich embed on death: cause, basic stats (score / location / dimension / XP) and a held/worn-gear image. |
| `autoDisconnectReport` | `false` | Post that **same** stats embed when a player leaves **while alive** (e.g. quits to the main menu). A death→quit is *not* double-reported. Off by default; reuses the death report's image settings (`showDeathReportImage`, `deathReportIconUrlTemplate`). |
| `disconnectReportTitleTemplate` | `👋 {player} left the game` | Disconnect report embed title. `{player}` → player name. |
| `disconnectReportEmbedColor` | `9807270` | Disconnect report embed colour, a decimal `0xRRGGBB` value (`0x95A5A6`, a muted grey). |
| `createThreadOnJoin` | `true` | Open one persistent thread per player (anchored to their first join). False = plain top-level join message per session. |
| `firstJoinMessageTemplate` | `🎮 **{player}** joined the game for the first time` | Top-level anchor message on a player's first ever join. |
| `threadNameTemplate` | `{player}` | Per-player thread name. |
| `threadAutoArchiveMinutes` | `10080` | Thread auto-archive inactivity: `60`/`1440`/`4320`/`10080`. |
| `advancementNamespaces` | `[]` | Advancement namespaces to announce (empty = all). |
| `onlyDisplayAdvancements` | `true` | Skip hidden advancements that have no title/icon. |
| `advancementMessageTemplate` | `{player} earned` | Attribution line above the advancement embed. `{player}`, `{advancement}`. |
| `relayDiscordToGame` | `true` | Relay Discord replies/threads into in-game chat (needs the bot token + Message Content intent). |
| `relayGameToDiscord` | `true` | Relay in-game chat to Discord through the webhook. |
| `discordToGameFormat` | `<{user}> {msg}` | How a relayed Discord message reads in-game. `{user}` = author, `{msg}` = text. |
| `presenceTrackUserIds` | `[]` | Discord user ids to track for the "last seen online" query API (`DiscordService.lastSeenOnline`). Non-empty needs the bot token + the **Presence** privileged intent; empty (default) requests no presence intent, so existing chat is unaffected. |

> `discordpresence-server.toml` holds secrets, so it is a **`COMMON` config — never synced to
> clients** (the bot token stays on the server). Only edit it on the server; don't commit it. A
> separate `discordpresence-client.toml` stores only your one-time network-access choice
> (`networkConsent`) — no secrets.

### Auto-responses ("whispers into the darkness")

When a player chats while no Discord conversation is active, the game shows a grey, system-style
flavour line ~0.3s after their own message — assembled at random from `{player} {verb} into the
{place}, {phrase}` (~2,000 combinations from the word-lists). It posts **nothing** to Discord;
it's purely in-game feedback that the message went out to a quiet channel. A relayed Discord
reply turns it off for that player, and it re-arms after `rearmMinutes` of Discord silence
(remembered across worlds in singleplayer). At most one fires per 30 seconds. These keys live
under a separate `[autoResponse]` section of `discordpresence-server.toml`:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master toggle for the in-game auto-responses. |
| `rearmMinutes` | `30` | Minutes of Discord silence before responses re-arm after a reply. |
| `aloneCooldownSeconds` | `30` | Min seconds between responses when the player is alone (a 30s floor always applies). |
| `aloneTemplate` | `{player} {verb} into the {place}, {phrase}` | How the alone whisper is assembled. |
| `verbs` / `places` / `phrases` | word lists | Random pools for the template's `{verb}` / `{place}` / `{phrase}`. |
| `groupCooldownSeconds` | `300` | Min seconds between responses when other players are online. |
| `groupMessages` | `["{player} mutters to themselves..."]` | Flat message pool used when others are online. |

## Bundling into another mod

Discord Presence ships **blank** — there's no webhook or token in its jar, so on its own it stays
off until a server owner fills in `discordpresence-server.toml`. A mod that **bundles** DP (e.g.
[Dungeon Train](https://github.com/bh679/dungeon-train-mc) via jarJar) can point it at a central
feed at runtime by registering a `DiscordCredentialsProvider` from its `@Mod` constructor:

```java
import games.brennan.discordpresence.config.DiscordCredentials;

// in your mod's constructor — webhookUrl(); botToken() defaults to "" (so you can supply a webhook only)
DiscordCredentials.register(() -> "https://your-relay.example/hook");
```

`getWebhookUrl()` / `getBotToken()` fold the provider's values in via `CredentialResolver`. The
default precedence is **provider-wins** — the bundled feed overrides a server owner's local config
(flip `CREDENTIAL_POLICY` to `CONFIG_WINS` to invert). With no provider registered, DP behaves
exactly as before.

> **Don't ship a secret in the jar.** A webhook URL — and *especially* a bot token — embedded in a
> distributed jar is extractable even when obfuscated, and Discord auto-revokes leaked tokens. The
> safe pattern is a small **relay you host**: return the relay's URL from `webhookUrl()` (DP posts
> to it exactly like a Discord webhook, as long as it forwards to Discord and returns Discord's
> response) and keep the real webhook + token server-side. Leave `botToken()` blank unless the relay
> also fronts the bot API.

**Driving your own death / disconnect report.** To show richer run stats than DP's generic ones,
call `DiscordService.get().postDeathReport(player, title, description, fields, items)` from your own
death *or* logout handler, and have your `DiscordCredentialsProvider` return `suppressAutoDeathReport()`
/ `suppressAutoDisconnectReport()` so DP's built-in auto reports don't double-post. This is how
Dungeon Train turns the disconnect report on (with its own stats) while standalone DP leaves it off.

**Querying a Discord user's "last seen online".** DP can track configured Discord users' presence and
expose it so a bundling mod can render *"Brennan was last seen online 5 minutes ago"*:

```java
// 1. Tell DP which Discord user ids to track (unioned with the admin's presenceTrackUserIds config):
DiscordCredentials.register(new DiscordCredentialsProvider() {
    @Override public String webhookUrl() { return "https://your-relay.example/hook"; }
    @Override public List<String> presenceTrackUserIds() { return List.of("342110421114945537"); }
});

// 2. Query it anywhere — absent-safe: Optional.empty() whenever presence is unknown.
Optional<Boolean> online = DiscordService.get().isDiscordUserOnline("342110421114945537");
Optional<Instant> seen   = DiscordService.get().lastSeenOnline("342110421114945537");
```

This needs DP running a **direct bot connection** (a `botToken` set) with the **Presence Intent**
enabled in the Developer Portal (Bot → Privileged Gateway Intents) — exactly like Message Content for
two-way chat. Enabling it requests the `GUILD_PRESENCES` intent *only* when `presenceTrackUserIds` is
non-empty, so installs that don't use it are unaffected. In **relay-mode** (the recommended secret-free
setup) DP holds no local gateway, so the query returns empty unless the relay serves presence — both
methods are absent-safe, so a consumer degrades gracefully meanwhile.

## Build

```bash
./gradlew build        # -> build/libs/discordpresence-neoforge-<version>.jar
./gradlew runServer    # dev dedicated server
./gradlew runClient    # dev client (single-player integrated server)
```

## License

[PolyForm Shield 1.0.0](LICENSE) — © Brennan Hatton.
