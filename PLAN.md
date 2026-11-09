# RapidSkies Network — Development Plan

> Velocity proxy + Paper 26.3 backends · Java clients 1.20 → 26.3 · Hosted on Pterodactyl (VPS/dedicated)
> Server address: **rapidskies.us** · Store: **store.rapidskies.us**
> Status: Phase 1 code complete, in-game checks pending (see §11) · Last updated 2026-11-09

---

## 1. Goals & guiding rules

1. **Every player from 1.20 to 26.3 gets a fair, working experience.** Gameplay never depends on something an older supported client can't see or do.
2. **No pay-to-win.** Anything that can be obtained with real money, directly or through the purchasable currency, must be cosmetic or pure convenience that doesn't change the outcome of any competitive or economic interaction. (See §8, compliance.)
3. **Long-term play without burnout.** Both launch gamemodes are persistent. Progress is never wiped, novelty comes from rotating content, and the design rewards consistent play over marathon grinding (see §5.3).
4. **Buy where mature, build where differentiating.** Use proven plugins for permissions, punishments, anti-cheat, the island engine, holograms and tab. Build custom code for each mode's unique features, **claims & towns**, the **auction house**, the currency, cosmetics, parties, and staff tooling.

---

## 2. Brand & style guide

Colors are sampled from `assets/RapidIcon.png` and `assets/RapidBlackIcon.png`.

| Token | Hex | Use |
|---|---|---|
| `rapid.primary` | `#FFD800` | Brand name, highlights, prefixes, primary buttons |
| `rapid.accent` | `#FFAA00` | Secondary highlight, numbers, currency amounts |
| `rapid.text` | `#FFFFFF` | Body text |
| `rapid.muted` | `#AAAAAA` | Secondary text, hints |
| `rapid.dark` | `#555555` | Separators (`»`, `┃`, `•`) |
| `rapid.success` | `#55FF55` | Confirmations |
| `rapid.error` | `#FF5555` | Errors, punishments |

Every supported client (1.20+) renders hex colors and gradients exactly, so there is no legacy-color fallback to design around.

**Message format** (MiniMessage, centralized in one `lang/` bundle; no hard-coded strings in plugins):

```
<#FFD800><bold>RAPID</bold></#FFD800><white><bold>SKIES</bold></white> <#555555>»</#555555> <white>Your message, with <#FFAA00>highlights</#FFAA00>.</white>
```

- Short prefixes per context: `⚡ Bolts`, `Party »`, `Staff »`, `Queue »`. Stick to glyphs in Minecraft's default font; emoji outside it render as boxes.
- Sidebar title: `RAPIDSKIES` in bold primary color. Footer line: `rapidskies.us` in primary color.
- MOTD line 2 rotates between news, the current Season or Frontier name, and `store.rapidskies.us`.
- Logo and MOTD icon: `server-icon.png` (64×64) is generated from `RapidBlackIcon.png`.

**Build palette** (hub and lobbies are floating sky islands, which ties to the "Skies" name):

- Primary: gold block, yellow wool/terracotta, glowstone, sponge for texture variety.
- Contrast: coal block, black wool/stained clay, polished blackstone and deepslate.
- Highlights: quartz, white wool, sea lantern, snow.
- Natural: grass, stone, clouds made of white wool and cobweb.
- **Note:** blocks added after 1.20 (tuff bricks, copper bulbs and grates, pale oak, resin, etc.) appear as ViaBackwards substitutes for 1.20 clients. Use them only for decoration. Anything that affects gameplay (parkour, map edges, bridges) uses blocks that exist in 1.20.

---

## 3. Network architecture

```
                         ┌────────────────────────────┐
  rapidskies.us ───────▶ │  Velocity 4.3.x  (proxy)    │  :25565 (only public game port)
  Players 1.20 → 26.3    │  LuckPerms · LiteBans · TAB │
                         │  rapid-proxy (custom)       │
                         └─────────────┬──────────────┘
          modern forwarding (secret)   │  internal network only
     ┌───────────┬────────────┬────────┼─────────────┬──────────────┐
     ▼           ▼            ▼        ▼             ▼              ▼
  hub-1       hub-2      skyrealms-1  smp-1      [gamemode 3]    build (staff only,
  Paper 26.3  Paper 26.3 (SkyBlock)   (Horizons  (reserved,      not linked to proxy
                                       SMP)       see §5.4)      for players)
     └───────────┴────────────┴────────┬─────────────┴──────────────┘
                                       ▼
                  MariaDB (persistent data) + Redis (pub/sub, presence, queues)
```

### Domains & DNS

| Record | Points to | Purpose |
|---|---|---|
| `rapidskies.us` A/AAAA | Website host (can be Cloudflare-proxied) | Website, news, rules, appeals |
| `_minecraft._tcp.rapidskies.us` SRV → `mc.rapidskies.us:25565` | — | Players type **`rapidskies.us`** in the client and get routed to the game server. SRV is supported back to 1.3, so it covers every supported client. |
| `mc.rapidskies.us` A | Dedicated server IP (**DNS-only, not proxied**) | Game traffic target |
| `store.rapidskies.us` CNAME | Tebex custom-domain target | Webstore |
| `panel.rapidskies.us` A | Panel host (Cloudflare-proxied + access rules) | Pterodactyl panel |

If the website will be hosted on the same box, the apex A record can point straight at the server instead, and the SRV record becomes optional. Keep `mc.` as a fallback address either way, and add DDoS protection for the game IP before launch (a host with Minecraft-aware filtering, or a TCP proxy such as TCPShield or Cloudflare Spectrum).

### Version support

- **Supported clients: 1.20 (protocol 763) through 26.3.** Velocity accepts every client protocol from 1.7.2 onward natively; backends run **Paper 26.3** (Java 25).
- **ViaVersion + ViaBackwards are installed on each Paper backend**, not on the proxy. That gives correct per-world block and item mappings and anti-cheat that sees the real client version. ViaRewind is not used: it only exists for 1.7–1.8 clients.
- Allowed versions are pinned in Velocity (`rapid-proxy` `minimum-protocol: 763`): anything below 1.20 gets a branded kick message. ViaVersion's `block-versions: ["<1.20"]` is the backstop on each backend.
- Re-check Via, Paper and Velocity compatibility before every upgrade, and never upgrade backends on the day a Minecraft version releases.

### Cross-version design rules (apply to all custom plugins)

| Area | Rule |
|---|---|
| Combat | **Vanilla combat in both modes**: attack cooldown, sweeping edge and shields all work as normal, in PvE and PvP. Every client from 1.20 up has the same mechanics, so no mode changes the timing. Combat items added after 1.20 (mace, wind charges) are optional or mode-specific, never required. |
| GUIs | Chest menus, 6 rows at most. No anvil or text input for core flows (use chat prompts or signs instead). Don't build core flows on server dialogs (1.21.6+), which 1.20 clients don't have. |
| Display | Sidebar, tab list, boss bars, titles and action bars are all fine, with hex colors and gradients. Sidebar score numbers can only be hidden on 1.20.3+, so layouts must still read well with the red numbers on 1.20–1.20.2. |
| Holograms/NPCs | Display entities (text, item and block displays) and interaction entities are fine; every supported client renders them. |
| Items | No gameplay that relies on a resource pack, or on item model components newer than 1.20 (`item_model`, string `custom_model_data`), which ViaBackwards drops for older clients. A resource pack is optional polish. |
| Blocks/items | Progression-critical blocks and items must exist in 1.20 or map cleanly through ViaBackwards. Newer content (trial chambers, the crafter, pale oak, resin, etc.) is fine as decoration or optional content, but never as a required step. |
| Chat | Set `enforce-secure-profile=false` on backends, and handle formatting server-side. |

### Hosting on Pterodactyl

The **Panel runs on its own server**, separate from the **game node** (one dedicated server, 16 cores / 64 GB RAM / NVMe recommended) that runs Wings and every game container below. Containers reach services on the node at `172.18.0.1`. MariaDB and Redis are best kept on the node too, so gameplay never depends on the panel machine; if they live on the panel server, containers use its private IP and its firewall admits only the node. See `infra/README.md` → Where things run.

| Server (egg) | RAM | Notes |
|---|---|---|
| velocity | 1.5 GB | Only public game allocation (`0.0.0.0:25565`) |
| hub-1, hub-2 | 3 GB each | Load-balanced by rapid-proxy |
| skyrealms-1 | 10–12 GB | Island world + Expedition world. Scale with more instances later (the island engine supports multi-server). |
| smp-1 | 12–14 GB | Heartland (persistent) + Frontier worlds (rotating). Pre-generate all worlds with Chunky and set world borders. |
| *gamemode-3* | *reserved ~8 GB* | Left unallocated until §5.4 is decided |
| build | 4 GB | Staff/builder only, FAWE, separate whitelist |
| staging | 4 GB | Mirror of a game server for testing updates |
| MariaDB | 4 GB | Use Pterodactyl's database host feature |
| Redis | 1 GB | Separate egg or a host-level service |

- **Security:** bind every backend allocation to the internal Docker interface (`172.18.0.1`) or to localhost, never to the public IP. Use Velocity modern forwarding with a long random secret. On the **game node**, firewall everything except 25565, the Wings API/SFTP ports (Wings 8080 or 443 and SFTP 2022, which the panel and staff connect to) and SSH (key-only). On the **panel server**, allow only HTTPS (behind Cloudflare or an IP allowlist) and SSH, plus MariaDB/Redis from the node's IP if they run there.
- **JVM:** Java 25 with Paper's recommended (Aikar-style) flags. Install spark on every server.
- **Backups:** Pterodactyl scheduled backups (hourly for skyrealms and smp, daily for the rest, stored on the node), nightly `mysqldump` on whichever machine runs MariaDB plus the panel's own database on the panel server, and an offsite copy to S3 or Backblaze B2 from both machines. Keep the panel's `.env` (`APP_KEY`) somewhere safe outside the server. Archive each retired Frontier world for a month before deleting it.
- **Scaling path:** add more Wings nodes. Game servers register themselves in Redis, so rapid-proxy discovers new instances without config edits.

---

## 4. Plugin stack

### Premade

| Purpose | Plugin | Where | Notes |
|---|---|---|---|
| Version support | ViaVersion, ViaBackwards | Paper | See §3 |
| Permissions | **LuckPerms** | Velocity + Paper | MariaDB storage, Redis messaging, temp parents for timed ranks |
| Punishments | **LiteBans** (paid) | Velocity | Network-wide bans, mutes and warnings, history, web interface for appeals |
| Anti-cheat | **Grim** (prediction-based, multi-version) + evaluate **Polar** or **Vulcan** (paid) during beta | Paper | Grim is the base layer. Add a paid secondary for X-ray, autoclicker and aim detection after alpha. Alerts go to staff chat and Discord. |
| Anti-X-ray | Paper's built-in anti-xray (engine mode 2) | smp, skyrealms | Essential in survival economies |
| Island engine | **SuperiorSkyblock2** (open source) | skyrealms | Islands, co-op, upgrades, missions, multi-server support. Unique features are built on its API in `rapid-skyrealms`, which saves months over a from-scratch engine. |
| Player shops | **QuickShop-Hikari** | smp | Chest shops. It must respect `rapid-claims` (shops only in claims you have Member or higher in), via its protection-check hook. |
| Pre-generation | **Chunky** + ChunkyBorder | smp | Pre-generate Heartland and every new Frontier before it opens |
| Scoreboard / tab | **TAB** | Velocity | Per-server layouts, MiniMessage, nametags and sorting |
| Holograms | **DecentHolograms** | Paper | Hub and lobby holograms |
| NPCs | **Citizens** | Paper | Hub gamemode NPCs, shopkeepers, quest givers |
| Placeholders | PlaceholderAPI (Paper), MiniPlaceholders (Velocity) | Both | |
| Block logging | **CoreProtect** | smp, skyrealms | Grief and rollback investigations |
| Building | FastAsyncWorldEdit, VoxelSniper | build only | |
| Chat filter | **ChatSentry** (paid) or custom filter in rapid-core | Paper | Swear/spam/advertising filter, can link to LiteBans |
| Store | **Tebex** (Velocity plugin) | Velocity | Webstore at `store.rapidskies.us`. Subscriptions for ranks, one-time Bolts bundles. |
| Performance | spark | All | |

### Custom (Gradle multi-module monorepo, Java 25)

| Module | Runs on | Responsibilities |
|---|---|---|
| `rapid-common` | — | Shared models, Redis pub/sub, HikariCP DB layer + versioned SQL migrations, MiniMessage lang loader, config |
| `rapid-proxy` | Velocity | Server registry and load balancing, **parties**, **friends**, join queue, cross-server `/msg`, staff chat, MOTD/branding, maintenance mode, version gate |
| `rapid-core` | All Paper | Player profiles, **Bolts economy API**, rank display (including **plus color**, §6.1), per-mode Vault economy provider, chat formatting, **cosmetics engine**, network XP/levels, quests and achievements, **Tailwind** rested bonus (§5.3), **staff mode**, GUI framework, combat-timing module, AFK and anti-farm detection |
| `rapid-hub` | Hub | Spawn, mode selector, double-jump, visibility toggle, parkour, leaderboards, cosmetics and Bolt shop menus |
| `rapid-skyrealms` | skyrealms | Expeditions, Realm Tiers, Trade Routes, seasons, server shop, mode quests (on the SuperiorSkyblock2 API) |
| `rapid-horizons` | smp | Frontier rotation and modifiers, Skyborne events, Trades, PvP toggle and Wildlands, Chapters |
| `rapid-claims` | smp | **Chunk claims, trust roles, protection, towns, plots, upkeep, Town Projects** (§4.1) |
| `rapid-market` | smp, skyrealms | **Auction house**, scoped per mode and per currency (§4.2) |
| `rapid-minigame` | *(deferred)* | Arena framework, built only if gamemode 3 is a match-based mode (§5.4) |

**Why custom for these pieces:** premade cosmetics, party, claim, auction and economy plugins rarely store data network-wide, and none fit a shared currency that has non-advantage rules. Owning claims and the auction house also means:
- Town Projects, Crowns sinks and Trades integrate directly instead of through another plugin's API limits.
- Duplication safety and economy logging are under our control.
- Minecraft updates never wait on a third-party release.

Building on `rapid-common` keeps one source of truth.

### 4.1 Custom claims & towns (`rapid-claims`)

**Claims**
- **Chunk-based**, in the Heartland only. Frontiers, the Nether and the End can't be claimed. Chunk granularity gives O(1) protection lookups (an in-memory map keyed by world + chunk), clean town borders, and borders that are easy to show on every client version.
- **Claiming UX:** `/claim` claims the chunk you're standing in. `/claim map` opens a 6-row chest GUI map of the surrounding chunks using colored glass panes (yours, your town's, someone else's, wilderness), and you click to claim or unclaim, with no text input. Borders are shown with particles plus temporary client-side block outlines.
- **Claim allowance:** 9 starter chunks. More chunks are earned through playtime and Trade levels, or bought with Crowns at a rising price (a Crowns sink). **Never sold for money or Bolts.**
- **Trust roles per claim:**

| Role | Can |
|---|---|
| Visitor | Walk through only |
| Trusted | Use doors, buttons, levers |
| Member | Also open containers, use farms and animals |
| Builder | Also build and break |
| Co-owner | Also manage trust and flags |

- **Claim flags** (owner toggles): PvP inside the claim (default off), mob spawning, fire spread, leaf decay, and entry denial for specific players. Explosions are always blocked inside claims.
- **Protection coverage**, each item a test case:
  - Block break/place, buckets, and crop trampling
  - Interacting with containers, doors, redstone, item frames, armor stands, lecterns, beds and signs
  - Damage to animals, villagers and display entities
  - Explosions: TNT, creepers, withers, end crystals, and beds/respawn anchors
  - Fluids, pistons, dispensers and hoppers acting across claim borders
  - Fire spread and burning, and tree/mushroom/sculk growth across borders
  - Portal creation, vehicles, projectiles (arrows hitting buttons, tridents), and chorus fruit/ender pearl teleports into deny-entry claims

**Towns**
- **Founding** costs 5,000 Crowns. The town owns its chunks, which must be contiguous and start from a town center chunk.
- **Town roles:** Mayor → Assistant → Resident → Recruit, with configurable permissions per role. Mayors can assign **plots** (town chunks owned by a resident) using the same trust system.
- **Town bank:** in Crowns. It pays **daily upkeep** scaled by chunk count, which is an economy sink. The chunk cap scales with resident count and completed Projects.
- **Town Projects** (§5.2) are part of this module: donation tracking, completion, and unlocking upgrades (town warp, shared vault, market stall, cosmetics).
- **Display:** a town tag in chat and tab, with colors from a limited palette of named chat colors (red is still reserved for staff). Also town chat and a town level based on completed Projects.
- **Inactivity:** personal claims of players inactive for 90+ days, and towns that miss upkeep for 14 days, become **ruins**. Ruins stay protected for another 7 days, during which the owner can reclaim by logging in or paying upkeep. After that they're unclaimed, and the builds stay in the world.

**Engineering**
- Stored in MariaDB, cached in memory per world, and written through on change. Redis cache invalidation is ready for when Horizons scales to more than one instance.
- A public `ClaimService` API that other modules use: QuickShop checks, CoreProtect investigations, Frontier/Wildlands rules, and Skyborne event regions.
- Admin: `/claimadmin` to inspect, transfer, unclaim or restore claims and towns, with every action logged to `staff_actions`.

### 4.2 Custom auction house (`rapid-market`)

**Scope:** one codebase deployed to Horizons and Skyrealms. Listings and currencies are **scoped per mode** (Crowns on Horizons, Coins on Skyrealms) and are never shared.

**Listing**
- Hold an item and run `/ah sell <price>`, with a duration of 12, 24 or 48 hours.
- **Listing fee of 2%** (non-refundable) and **5% sales tax** on completed sales. Both are economy sinks.
- Max active listings is the same for everyone: 10, rising with Trade or island level. **It's never a rank perk.**

**Browsing (chest GUI)**
- Categories: blocks, tools & armor, food, farming, redstone, misc.
- Sort by price, newest, or ending soon. Search uses a chat prompt, not an anvil.
- Each listing's lore shows the seller, price, time left, and **the item's true name**. Clients older than the server see ViaBackwards substitutes for items added after their version, so the lore tells them what they're actually buying.
- **Price history:** the 7-day average sale price is shown when listing and when buying. It also feeds the economy health dashboard.

**Delivery**
- Sale proceeds are credited instantly.
- Expired, cancelled or bought-while-full-inventory items go to a **collection bin** (`/ah collect`). Items are never dropped on the ground and never lost.

**Integrity and anti-duplication**
- Removing the item from the inventory and inserting the listing happen in **one DB transaction**.
- Buying is a single atomic conditional update (`status = 'ACTIVE' → 'SOLD'`, which must affect exactly 1 row), so two buyers on two servers can never both win.
- Items are stored with Paper's `ItemStack#serializeAsBytes`, which upgrades item data across Minecraft versions.
- Every action is written to `mode_economy_ledger`.

**Notifications:** sales notify the seller over Redis on whatever server they're on, or are queued until their next login.

**Moderation**
- `/ah admin` to view, remove or return listings, plus per-player transaction history.
- Alerts on suspicious patterns, such as repeated far-below-average sales to the same buyer, which suggest alt transfers or real-money trading.

**Later (v2):** bid-style auctions and buy orders.

---

## 5. Gamemodes

RapidSkies launches with **two persistent gamemodes**. Both are long-term worlds where progress is never wiped, and each has its own earn-only economy. A third mode is chosen after launch (§5.4).

### 5.1 Skyrealms: co-op SkyBlock

**Core loop:** start on a small floating island, expand it, automate resources, grow an island economy, and climb the island-value leaderboards.

**Unique elements:**
- **Expeditions:** every few hours a temporary resource island drifts into a shared Expedition world, each with a theme (crystal caverns, sunken ship, overgrown ruins). Islands race to harvest it before it drifts away. Loot is split per contributor, so there's no last-hit stealing. This creates a scheduled social moment and a reason to leave your island.
- **Realm Tiers:** island progression unlocks new biome "realms" through a quest-driven chain (Meadow → Desert → Frost → Nether Rift → Sky Citadel). Each tier adds new resources and mechanics, so there's always a visible next goal and no endless flat grind.
- **Trade Routes:** islands can sign recurring trade contracts with other islands (for example, 64 cactus delivered weekly for 2,000 coins). Delivery is automated through a contract chest. This creates an interdependent player economy and gives smaller islands a niche.
- **Seasons (~3 months):** the island-value leaderboard resets and seasonal cosmetic titles and badges are awarded. **Islands themselves persist.**

**Economy:** **Skyrealms Coins** are earned only, by selling to the server shop, completing Trade Routes, and trading on the auction house. Coins can't be bought, and they never convert to or from Bolts or SMP Crowns.

### 5.2 Horizons: survival SMP

**Core loop:** survival on a protected, persistent main world. Claim land, build, trade, and join or found a town, with rotating adventure worlds providing resources and novelty.

**Unique elements:**
- **Heartland + Frontiers:** the **Heartland** is the permanent, claimable, never-reset overworld for building. **Frontiers** are monthly resource worlds, each with a **modifier** that changes how it plays: *Overgrowth* (dense forests, more mob variety), *Shattered Skies* (floating islands with rare ores), *Deep Dark* (huge caves, scarce surface), *Drought* (deserts, water is precious). Frontiers can't be claimed and reset with a week's warning. The Nether and End follow the same rotation, and a community dragon fight event opens each new End. Players get fresh exploration every month without ever losing their builds.
- **Skyborne Events:** floating sky fortresses periodically appear above the current Frontier. They're cooperative PvE dungeons with a boss, scaled to the number of participants, and rewards are per-participant. Fortresses are announced network-wide 10 minutes ahead.
- **Towns & Town Projects:** players found towns through the custom `rapid-claims` system (§4.1). Towns can take on **Projects**, community goals funded by member donations of resources and Crowns (for example, "Build the Lighthouse: 2,000 stone bricks, 64 glass, 5,000 Crowns"). Completed projects unlock town upgrades such as a town warp, a shared storage vault, a market stall at spawn, and banner and title cosmetics. This gives groups long-term shared goals.
- **Trades:** each player picks up to 2 professions (Miner, Farmer, Hunter, Builder, Enchanter, Fisher), which they can switch on a cooldown. Trades provide Crown income paths and small side-grade perks with **soft caps**, so a casual player isn't hopelessly behind a no-lifer.
- **Opt-in PvP:** PvP is off by default in the Heartland. `/pvp` toggles it, with a 5-minute cooldown to stop toggle abuse. Each Frontier has a **Wildlands** zone with forced PvP and richer resources, a risk/reward choice for players who want it. Duels are available anywhere by mutual request.
- **Chapters:** each month's Frontier is tied to a lore "Chapter" with a collection log (rare finds, event participation, exploration milestones). Completing a Chapter awards a cosmetic, such as a title, a banner pattern or a trail. Missing one never affects gameplay.

**Economy:** **Crowns** are earned only, by selling to players through chest shops and the auction house, from Trades, quests and Skyborne events. They're never purchasable and never convertible to Bolts or Coins. Sinks include auction house fees, town upkeep, Project funding, and cosmetic Crown-shop items, which keep inflation down so new players can still compete.

### 5.3 Anti-burnout design (applies to both modes)

| Principle | Implementation |
|---|---|
| No progress wipes | Islands, claims, towns and inventories persist. Only leaderboards and Frontier worlds rotate. |
| Reward consistency, not marathons | **Tailwind:** offline time accrues a rested bonus (+50% mode XP and Trade income, up to 3 days banked) that's spent while playing. Returning players catch up, and nobody needs 8-hour sessions. |
| Flexible goals | Daily quests bank for up to 3 days, so they don't expire at midnight. Weekly quests are the main goal and can be done on any day. |
| Soft caps on grinding | Diminishing returns on repetitive income (auto-farms, mob grinders, Trade XP) after a daily threshold. |
| Novelty over power creep | New content comes as new places and mechanics (Frontiers, Expeditions, Realm Tiers, Chapters), not bigger numbers. |
| FOMO only on cosmetics | Seasonal and Chapter exclusives are cosmetic. Missing one never weakens a player. |
| Healthy economy | Coin sinks and inflation monitoring through an admin dashboard of money supply per mode, so prices stay reachable for new players. |
| Catch-up for new players | New-player guide quests, starter boosts from Tailwind, and a mentor reward for vets who help (Bolts for referred players reaching milestones). |

### 5.4 Gamemode 3 (to be decided)

The architecture reserves a server slot, and `rapid-minigame` is deferred until the mode is chosen. Recommended timing: decide about 6–8 weeks after launch, using telemetry on where players spend their time and which session lengths are missing. Since both launch modes are long-session and persistent, a **short-session, match-based mode** would complement them best. Candidates from earlier planning:

- **Skyfall:** a SkyWars variant with collapsing outer islands, updraft vents and supply drops (5–8 min).
- **Core Rush:** an objective team mode with Cores, generators, Overdrive phases and a team upgrade tree (15–20 min).

---

## 6. Monetization: ranks, currency and store

### 6.1 Monthly ranks: Rapid+, Rapid++, Rapid+++

Ranks are sold as **Tebex subscriptions** at `store.rapidskies.us` and are also available as **30-day passes bought with Bolts**. Higher tiers include all perks of lower tiers. Prices are starting points to tune in beta.

**Prefix format:** the word `RAPID` always uses the brand color (`#FFD800`), and the pluses use the player's chosen **plus color**:

```
[RAPID+]  Steve: hello          ← "+"   in chosen color
[RAPID++] Alex: hello           ← "++"  in chosen color
[RAPID+++] Notch: hello         ← "+++" in chosen color, RAPID in bold
```

**Plus color (`/plus`):**
- `/plus` opens a GUI showing a preview of your prefix in each color. The choice is stored in the player's profile in `rapid-core`, synced network-wide over Redis, and rendered in chat, tab, nametags and the sidebar through a placeholder (`%rapid_plus_color%`).
- **Colors are drawn from the 16 named chat colors**, so each one has a clear name in `/plus`. The palette widens with tier:

| Tier | Available plus colors |
|---|---|
| Rapid+ | White, Yellow, Gold, Aqua, Green, Light Purple (6) |
| Rapid++ | + Blue, Dark Blue, Dark Aqua, Dark Green, Dark Purple (11) |
| Rapid+++ | + Gray, Dark Gray, Black (all 14 non-reserved colors), plus **gradients** |

- **Red and Dark Red are reserved** for staff prefixes, to prevent impersonation.
- Extra plus colors and gradients can be earned as achievement or Chapter rewards, but only players with an active rank can equip them.
- If a rank lapses, the color is remembered and restored when the rank returns.

| | **Rapid+** | **Rapid++** | **Rapid+++** |
|---|---|---|---|
| Price (subscription) | $4.99/mo | $9.99/mo | $19.99/mo |
| Bolts pass (30 days) | 1,000 ⚡ | 2,000 ⚡ | 4,000 ⚡ |
| Prefix | `[RAPID+]` | `[RAPID++]` | **`[RAPID+++]`** |
| Plus color choices | 6 | 11 | 14 + gradients |
| Monthly Bolts stipend | 100 | 250 | 600 |
| Cosmetic catalog access | Rapid+ set | + Rapid++ set | + Rapid+++ set + early access to new drops |
| Hub perks | Double-jump trail | + Hub flight | + Hub pets |
| Party size | 6 | 8 | 12 |
| Friends list | 150 | 250 | 500 |
| Queue priority on full servers | ✔ | ✔ | ✔ (highest) |
| `/nick` (staff-audited) | — | — | ✔ |
| Chat emotes, colored `/msg` | ✔ | ✔ | ✔ |

**Mode-specific perks (cosmetic only):**

| Mode | Rapid+ | Rapid++ | Rapid+++ |
|---|---|---|---|
| Skyrealms | Island name hologram color | + ambient island particles | + animated island title, extra warp-sign styles |
| Horizons | `/hat`, claim-border particle style | + exclusive banner patterns, nametag emblem | + town title effects, exclusive trail |

**Explicitly *not* sold, at any tier:** kits, gear, items, extra claim blocks, extra homes, extra co-op slots or island size, `/fly` outside the hub, `/craft` / `/ec` / `/repair` shortcuts, Trade slots, XP, coin or Crown multipliers, larger Tailwind banks, cooldown reductions, or Frontier early access.

### 6.2 Bolts ⚡: network currency

Bolts are one global currency stored in `rapid-core`'s DB. They can be bought on the store or earned by playing.

**Earning** (targets about 50–70 ⚡/day for an active free player, so a free player can earn Rapid+ in roughly 2–3 weeks):

| Source | Bolts |
|---|---|
| Daily login streak | 5 → 25 (day 7+) |
| Active playtime (AFK-checked) | 2 per 15 minutes, capped at 20/day |
| Daily quests (3 per day, bank up to 3 days) | 10 each |
| Weekly quests | 75 |
| Expedition or Skyborne event participation | 10–20 |
| Chapter completion / Realm Tier unlock | 100 |
| Network level-up | 25 |
| Achievements | 10–250 |
| Mentor milestone (a referred new player reaches a goal) | 50 |

**Anti-farm:** daily caps per source; AFK and alt detection; no Bolts from activity on a single IP with multiple accounts.

**Spending:**
- Cosmetics (permanent unlocks) and the weekly rotating **Featured Shop**
- Rank passes (30 days, stacking)
- Season Pass premium track (cosmetic rewards only)
- Nickname colors, chat tags, profile badges

**Never sold for Bolts:** anything that affects combat, resources, claims, islands, or either mode's economy. Bolts also can't be traded between players, which prevents real-money trading and laundering.

**Store bundles (Tebex):** 500 ⚡ $4.99 · 1,100 ⚡ $9.99 · 2,400 ⚡ $19.99 · 6,500 ⚡ $49.99.

**No paid randomness:** no crates or keys can be bought with real money or Bolts. If mystery boxes exist, they come from free play only, contain cosmetics only, and show their odds.

### 6.3 Store integration (store.rapidskies.us)

- Tebex webstore on the custom domain `store.rapidskies.us` (CNAME + Tebex SSL), themed with the brand palette and logo. The Tebex plugin runs on Velocity.
- Packages run commands such as `rapid grant rank <uuid> rapidplusplus 30d <txn>` and `rapid bolts add <uuid> 1100 <txn>`.
- Every grant is **idempotent per Tebex transaction ID** and logged.
- Ranks use LuckPerms **temporary parents**. Stacking extends time, and the highest active tier wins for display.
- Chargebacks trigger an automatic revoke plus a flag for staff review.
- `/store` and `/buy` in-game print a clickable `store.rapidskies.us` link.

---

## 7. Network-wide retention systems

- **Network level:** XP from both modes; level rewards are Bolts and cosmetics.
- **Quests:** 3 daily (banked) and 2 weekly, alternating between modes to encourage trying both.
- **Season Pass** (about 8 weeks, aligned with Chapters): free track plus a premium track bought with Bolts. Cosmetics only.
- **Leaderboards:** hub holograms and `/lb` for island value, town size, Trade levels, Chapter completion, and parkour times.
- **Social:** parties, friends, cross-server messaging, friend join notifications, "join friend" from the friends GUI.
- **Hub parkour** with a time leaderboard and cosmetic rewards.
- **Events:** Expeditions, Skyborne fortresses, Frontier openings, End dragon fights, build contests (judged on the build server, cosmetic prizes).
- **Discord:** account linking (a one-time Bolts reward), report and punishment feeds, event announcements, a Frontier rotation countdown.

---

## 8. Compliance checklist (Minecraft EULA & Usage Guidelines)

Before launch, re-read the current [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines) (server/commercial section) and confirm each item:

- [ ] Nothing bought with money or Bolts gives a gameplay advantage (§6.1 "not sold" list is enforced in code: shop items are tagged `COSMETIC` or `CONVENIENCE`, and the shop refuses anything else).
- [ ] No paid randomized rewards.
- [ ] Bolts are non-tradable and can't be converted into Coins or Crowns.
- [ ] The store at `store.rapidskies.us` clearly states "cosmetic only" and doesn't use Mojang/Microsoft branding.
- [ ] The server isn't presented as official or endorsed by Mojang.
- [ ] Tebex terms, refund policy and a privacy policy are published on `rapidskies.us`.
- [ ] Any perk later flagged as a gray area (for example, queue priority) is reviewed against the current guidelines.

---

## 9. Staff & moderation

**LuckPerms staff track:** Helper → Moderator → Sr. Moderator → Admin → Owner, with Builder and Developer as separate groups. Staff prefixes use the reserved red shades.

**Staff mode** (`rapid-core`): vanish, freeze (with a chest-menu notice), inventory/ender chest inspect, teleport to player, random teleport to players, CPS/reach view from the anti-cheat, X-ray ore-ratio view, and a punishment GUI that shortcuts LiteBans templates.

**Survival-specific tools:** CoreProtect lookups and rollbacks, `/claimadmin` and island admin overrides, `/ah admin` and shop transaction lookups, and economy anomaly alerts for sudden large gains or suspicious auction patterns.

**Reports:** `/report <player> <reason>` creates a report with a chat snapshot and location. It's shown in a staff GUI and posted to Discord, with claim and resolve tracking.

**Logging:** chat, commands, private messages and economy transactions are logged to MariaDB with retention limits. `/history` is backed by LiteBans.

**Anti-cheat workflow:** alerts go to staff chat (filterable by severity), auto-punish only at high-confidence thresholds, and everything else is reviewed manually with replay of the logged violations.

**Punishment templates:** standardized durations for hacking, X-ray, duping, griefing, scamming, toxicity and advertising. Appeals go through `rapidskies.us` or Discord.

---

## 10. Data model (MariaDB, versioned migrations)

- `players` (uuid, name, first_join, last_join, last_server, last_seen, network_xp, locale, **plus_color**, tailwind_banked). `last_seen` is stamped by the proxy when a player leaves the network and cleared when the next login turns that absence into Tailwind.
- `bolts_ledger` (id, uuid, delta, reason, source, txn_id UNIQUE, created_at), with `bolts_balance` as a cached view. Use a ledger, not a single balance column, for auditability.
- `mode_economy_ledger` (mode, uuid, delta, reason, created_at) for Coins and Crowns, kept separate per mode
- `cosmetics_owned` (uuid, cosmetic_id, source, acquired_at); `cosmetics_equipped` (uuid, slot, cosmetic_id)
- `quests_progress`, `achievements`, `season_progress`, `chapter_progress`
- `horizons_trades` (uuid, trade, level, xp)
- **Claims:** `claims` (world, chunk_x, chunk_z, owner_type [player/town], owner_id, flags, claimed_at; PK world+chunk), `claim_trust` (owner_type, owner_id, uuid, role), `claim_allowance` (uuid, earned, purchased)
- **Towns:** `towns` (id, name, tag, tag_color, mayor, bank, level, center_chunk, upkeep_paid_until, state [active/ruins]), `town_members` (town_id, uuid, role, joined_at), `town_plots` (world, chunk_x, chunk_z, town_id, resident_uuid), `town_projects` (town_id, project_id, contributions, state)
- **Market:** `market_listings` (id, mode, seller, item_blob, item_name, category, price, created_at, expires_at, status [ACTIVE/SOLD/EXPIRED/CANCELLED/REMOVED], buyer, sold_at), `market_collection` (id, mode, uuid, item_blob, reason), `market_price_history` (mode, item_key, day, avg_price, volume)
- `skyrealms_trade_routes` (id, seller_island, buyer_island, item, qty, price, interval, state)
- `frontier_history` (id, modifier, opened_at, closed_at, archive_path)
- `friends` (two rows per friendship), `friend_requests`. Parties and the join queue are ephemeral and live in the proxy's memory: there is one proxy and a restart drops every player anyway. Move them to Redis (`party:<id>`, `queue:<target>`) if a second proxy is ever added.
- `reports`, `staff_actions` (staff, action, target, server, detail: freezes, inspections, vanish and staff-mode toggles), `chat_log`
- `store_transactions` (txn_id, uuid, package, status, timestamps)

SuperiorSkyblock2 keeps its own tables in the same MariaDB instance, under its own schema.

Redis (every key and channel is prefixed `rapid:`, since LuckPerms shares the instance): `server:<id>` heartbeat and state, `maintenance`, the sets `vanished` and `frozen` (staff state that survives relogs and server switches), `shared-ip` (written by the proxy: accounts sharing an IP with one that joined earlier, which earn no activity Bolts), and `playtime:<day>:<uuid>` active-second counters; plus pub/sub channels for profile and plus-color updates, Bolts changes, maintenance, connect requests from backends to the proxy (`connect`), `vanish`, `freeze`, `mode-balance` (a Coins/Crowns balance changed on another server), and cache invalidation. Private messages, party chat and staff chat run on the single proxy and need no channel; `presence:<uuid>` and per-party keys join them only with a second proxy.

**Migrations:** each module keeps `V<n>__<name>.sql` scripts plus an `index` file under `db/migration/<module>/`, applied once under a MariaDB named lock and checksum-verified on every start (`rapid-common` `Migrator`). This replaced Flyway: it's one small class with no classloader or relocation quirks inside shaded plugin jars. Shipped scripts are never edited; fixes go in a new version.

---

## 11. Roadmap

Durations assume 1–2 developers plus 1–2 builders working in parallel. Phases overlap where noted.

| Phase | Weeks | Deliverables |
|---|---|---|
| **0. Foundation** | 1–2 | `rapidskies.us` DNS + SRV, Pterodactyl, eggs, Velocity + Paper 26.3, modern forwarding, firewall/DDoS protection, Via stack, LuckPerms, MariaDB/Redis, backups, Gradle monorepo with CI building the plugin jars, staging server |
| **1. Core platform** | 3–6 | `rapid-common`, `rapid-proxy` (registry, parties, friends, queue, MOTD), `rapid-core` (profiles, chat, rank display with plus color, Bolts ledger, per-mode economy, staff mode, GUI framework, combat timing, Tailwind), LiteBans, Grim, TAB |
| **2. Hub** | 4–8 | Hub build (builders start in week 1), `rapid-hub`, mode selector, NPCs, parkour, leaderboards |
| **3a. Claims & towns** | 5–11 | `rapid-claims`: chunk claims, claim map GUI, trust roles, flags, full protection suite, towns, plots, upkeep, ruins, Town Projects, `/claimadmin`, QuickShop hook |
| **3b. Horizons SMP** | 7–16 | Heartland generation, Trades, Frontier rotation system + 2 launch modifiers, Skyborne events (1 fortress design), PvP toggle and Wildlands, chest shops, Chapter 1 |
| **4a. Auction house** | 10–13 | `rapid-market` for both modes: listings, browse/search GUI, collection bin, price history, Redis notifications, `/ah admin`, concurrency and anti-dupe test suite |
| **4b. Skyrealms** | 8–17 | SuperiorSkyblock2 setup and island schematics, Realm Tiers 1–3, Expeditions (3 themes), Trade Routes, server shop |
| **5. Monetization & retention** | 8–16 | Cosmetics engine and launch catalog, Bolt shop, Featured Shop, `/plus` GUI, quests, levels, Season Pass, Tebex store at `store.rapidskies.us`, compliance review |
| **6. Beta & launch** | 17–22 | Closed beta (Horizons first, then both), open beta, load test with bots (for example, SoulFire), version test matrix, anti-cheat and X-ray tuning, economy tuning, launch |
| **7. Gamemode 3** | launch + 6–8 | Choose the mode from telemetry (§5.4), then build `rapid-minigame` if it's match-based |

### Phase 1 progress

Done (with tests; the MariaDB/Redis ones run in CI through Testcontainers):

- [x] `rapid-common`: YAML config, MiniMessage lang bundles with brand tags and per-server overrides, HikariCP pool, migrations, Redis bus (pub/sub with reconnect), server registry and hub selection, player profiles, Bolts and per-mode ledgers (atomic debits, idempotent `txn_id`), rank / staff role / plus-color model
- [x] `rapid-proxy`: joins and kicks routed to the least-loaded live hub, auto-registration of new backends (private IPs only), 1.20 version gate, rotating MOTD with network player count, `/maintenance` (Redis-backed, staff bypass), `rapid bolts add|remove|balance` for Tebex, logins refused until MariaDB and Redis are reachable
- [x] `rapid-core`: heartbeat, profile load on pre-login, chat with rank/staff prefixes and plus color (via LuckPerms groups), `/plus` (clickable list), `/bolts`
- [x] Deploy config: `rapid-proxy` / `rapid-core` config patches, `RAPID_DB_*` secrets, staging on its own schema, LuckPerms permissions
- [x] `rsdeploy.py` ships the custom jars: `dist/` entries in plugin sets come from `build/dist/`, compared by SHA-256 (their names never change, and Gradle builds them reproducibly, so an unchanged build isn't re-uploaded); `plugins --restart`
- [x] `rapid-proxy` social layer: parties (invite/accept/kick/promote/warp, `/pc`, members follow the leader, size by rank), friends (requests, list with server and join button, join/leave notices, size by rank; `friends` + `friend_requests`, core V2), join queue (`/play`, `/leavequeue`, rank priority, action-bar position, backends request moves over Redis `rapid:connect`), cross-server `/msg` + `/r`, staff chat `/sc`
- [x] `rapid-core`: chest-menu framework (`gui.Menu`), `/plus` as a chest GUI, PlaceholderAPI expansion (`%rapid_prefix%`, `%rapid_plus_color%`, `%rapid_rank%`, `%rapid_staff%`), `RapidCore#connect` for the hub selector
- [x] LiteBans (own MariaDB schema, mute list covers proxy chat commands), Grim and TAB (branded header/footer, team-first sorting) in the `network.yml` plugin sets
- [x] `rapid-core` staff mode (§9): `/staff` swaps the inventory (kept in persistent player data, so a crash can't lose it) for tools: random teleport, inventory/ender chest inspection (read-only snapshots), freeze, player list, vanish; staff can't drop, pick up, build or fight. `/vanish` is network-wide (Redis) and hides staff from friend lists, friend notices and `/msg` on the proxy. `/freeze` locks movement, items and commands on the backend and the proxy (so `/play` is no escape), shows a notice menu plus title reminders, survives relogs, and alerts staff chat on logout. Every use is logged to `staff_actions` (core V3)
- [x] AFK detection (only view turns, chat and commands count, so water streams and auto-clickers stay AFK), `/afk`, `PlayerAfkChangeEvent`
- [x] Anti-farm: `EarnGuard` for every activity reward (no Bolts while AFK, none for accounts sharing an IP with one that joined earlier, network-wide daily caps per reason) and playtime Bolts (2 per 15 active minutes, 20/day, progress carried across server switches)
- [x] Tailwind (§5.3): the proxy turns time away into Tailwind on login (2h per day away, at most 3 days counted; 2h starter bank for new players); gamemode servers spend it while not AFK; `tailwind().multiplier(player)` for the mode plugins, `/tailwind`, `%rapid_tailwind%`
- [x] Per-mode Vault economy: VaultUnlocked on gamemode servers; rapid-core registers Coins or Crowns (`server.gamemode`) with cached balances and ledger-guarded writes, rounding fractional amounts against the receiver; ledger rows name the calling plugin (`vault:SuperiorSkyblock2`)
- [x] TAB-Bridge on the backends and VelocityScoreboardAPI on the proxy; TAB shows `%rapid_prefix%` in the tab list and nametags and `%rapid_afk%` after names

Next:

- [ ] Check on 1.20.1 and 26.3: LiteBans mutes block `/msg`, `/r` and `/pc`; party follow and the queue action bar; `/plus` menu items and lore; staff mode tools, the freeze notice and title; `[RAPID+++]` nametags; vanished staff missing from the tab list
- [ ] Tune in beta: Tailwind rate and starter bank, playtime Bolts, AFK timeout
- [ ] Later staff tooling from §9 that needs other plugins' APIs: CPS/reach view (Grim), X-ray ore ratios, LiteBans punishment GUI

**Recommended launch strategy:** launch both modes together. With only two modes, launching one at a time would feel thin. Run Horizons as the first closed beta, though, since it relies more on premade plugins and will stress-test the core platform early. Don't wipe beta progress on Skyrealms or Horizons at launch.

### Test matrix (every release)

Clients: **1.20.1, 1.20.4, 1.21.11, 26.1.2, 26.3.** For each client, check:
- Login via `rapidskies.us` (SRV) and `mc.rapidskies.us`
- Chat colors, including every plus color and gradient
- Sidebar and tab, every GUI, holograms and NPCs, cosmetics visibility
- Skyrealms: create an island and complete one Expedition
- Horizons: claim land via `/claim map`, found a town, use a chest shop, travel to the Frontier, and fight in a Skyborne event
- Both modes: list, buy and collect on the auction house, and confirm the true item name shows for newer items on 1.20

**Automated tests (run in CI on every build):**
- MockBukkit unit and integration tests for the full claim protection matrix (§4.1).
- Auction house concurrency tests: parallel buy attempts on one listing, a server crash in the middle of listing or buying, and buying with a full inventory.

---

## 12. Post-launch live-ops cadence

- **Weekly:** Featured Shop rotation, quest rotation, Expedition themes, staff report review, economy health check.
- **Monthly:** new Frontier with a new or remixed modifier, a new Horizons Chapter, a Skyborne fortress variant.
- **Each season (~8–12 weeks):** new Season Pass, cosmetic drop, Skyrealms leaderboard reset with new Realm Tier(s), Trade and Town Project additions.
- **Each Minecraft release:** wait for stable Paper + Via + Velocity + SuperiorSkyblock2 builds, test on staging against the full matrix, then upgrade.

---

## 13. Open decisions

1. Gamemode 3 (decide about 6–8 weeks post-launch; see §5.4).
2. Paid secondary anti-cheat: Polar or Vulcan (decide after alpha data).
3. Claim precision: chunk-based is planned. Revisit sub-chunk claims inside town plots if beta players ask for finer control. Bid auctions and buy orders (`rapid-market` v2) are scheduled post-launch.
4. Final prices, Bolt earn rates and Tailwind numbers (tune with beta telemetry).
5. Website host: separate from the game box (SRV setup, recommended) or on the same box.
