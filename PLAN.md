# RapidSkies Network — Development Plan

> Velocity proxy + Paper 26.3 backends · Java clients 1.20 → 26.3 · Hosted on Pterodactyl (VPS/dedicated)
> Server address: **rapidskies.us** · Store: **store.rapidskies.us**
> Status: Phases 1–4 complete; Phase 5 (monetization & retention: cosmetics, Bolt shop, quests, levels, Season Pass, store) code complete, in-game checks pending (see §11) · Last updated 2026-11-11

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
| World management | **Multiverse-Core** 5 | smp | Manages the Heartland (imported as it is) and every Frontier world. `rapid-horizons` registers its per-modifier terrain generators with it (`rapid-horizons:<modifier>`), so each world's generator is recorded in `worlds.yml`. Its spawn, respawn, game mode and flight handling are off: those are rapid-core's |
| Scoreboard / tab | **TAB** | Velocity | Per-server layouts, MiniMessage, nametags and sorting |
| Holograms | Text display entities (in `rapid-hub`); **DecentHolograms** only if staff want hand-placed ones | Paper | Leaderboards and NPC labels are rapid-hub's own text displays, rebuilt from its config on every start |
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
| `rapid-core` | All Paper | Player profiles, **Bolts economy API**, rank display (including **plus color**, §6.1), per-mode Vault economy provider, chat formatting, **cosmetics engine**, network XP/levels, quests and achievements, **Tailwind** rested bonus (§5.3), **staff mode**, **build mode** for the protected spawn worlds, GUI framework, combat-timing module, AFK and anti-farm detection |
| `rapid-essentials` | All Paper | Everyday commands (EssentialsX-style, built on rapid-core's spawn, AFK, vanish, freeze and economy): `/spawn`, `/back`, `/tpa`, homes, warps, `/balance` `/pay` `/baltop` `/eco`, `/afk`, `/list`, `/seen`, `/hat`, info pages, network-wide `/broadcast`, and staff tools (`/gamemode`, `/fly`, `/god`, `/heal`, `/give`, `/repair`, `/craft`, `/ec`...). One teleport path (cooldowns, warmup, safe landing, guards other plugins add) |
| `rapid-hub` | Hub | Spawn, mode selector, double-jump, visibility toggle, parkour, leaderboards, hotbar items for the cosmetics, Bolt shop and quest menus (the menus themselves are rapid-core's, so they work on every server) |
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

**Scope:** one codebase deployed to Horizons and SkyRealms. Listings and currencies are **scoped per mode** (Crowns on Horizons, Coins on SkyRealms) and are never shared.

**Listing**
- Hold an item and run `/ah sell <price>`, with a duration of 12, 24 or 48 hours.
- **Listing fee of 2%** (non-refundable) and **5% sales tax** on completed sales. Both are economy sinks.
- Max active listings is the same for everyone: 10, rising with Trade or island level (Horizons: +1 per 10 total Trade levels, up to +10). **It's never a rank perk.**

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

**Notifications:** sales notify the seller over Redis on whatever gamemode server they're on, or are queued until their next login on one (the hub doesn't run the auction house).

**Moderation**
- `/ah admin` to view, remove or return listings, plus per-player transaction history.
- Alerts on suspicious patterns, such as repeated far-below-average sales to the same buyer, which suggest alt transfers or real-money trading.

**Later (v2):** bid-style auctions and buy orders.

---

## 5. Gamemodes

RapidSkies launches with **two persistent gamemodes**. Both are long-term worlds where progress is never wiped, and each has its own earn-only economy. A third mode is chosen after launch (§5.4).

### 5.1 SkyRealms: co-op SkyBlock

**Core loop:** start on a small floating island, expand it, automate resources, grow an island economy, and climb the island-value leaderboards.

**Unique elements:**
- **Expeditions:** every few hours a temporary resource island drifts into a shared Expedition world, each with a theme (crystal caverns, sunken ship, overgrown ruins). Islands race to harvest it before it drifts away. Loot is split per contributor, so there's no last-hit stealing. This creates a scheduled social moment and a reason to leave your island.
- **Realm Tiers:** island progression unlocks new biome "realms" through a quest-driven chain (Meadow → Desert → Frost → Nether Rift → Sky Citadel). Each tier adds new resources and mechanics, so there's always a visible next goal and no endless flat grind.
- **Trade Routes:** islands can sign recurring trade contracts with other islands (for example, 64 cactus delivered weekly for 2,000 coins). Delivery is automated through a contract chest. This creates an interdependent player economy and gives smaller islands a niche.
- **Seasons (~3 months):** the island-value leaderboard resets and seasonal cosmetic titles and badges are awarded. **Islands themselves persist.**

**Economy:** **SkyRealms Coins** are earned only, by selling to the server shop, completing Trade Routes, and trading on the auction house. Coins can't be bought, and they never convert to or from Bolts or SMP Crowns.

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

**Prefix format:** no brackets, just the rank name and its colors. The word `RAPID` always uses the brand color (`#FFD800`), and the pluses use the player's chosen **plus color**. Team prefixes follow the same style (`OWNER`, `MOD`, …) in the reserved reds:

```
RAPID+ Steve: hello            ← "+"   in chosen color
RAPID++ Alex: hello            ← "++"  in chosen color
RAPID+++ Notch: hello          ← "+++" in chosen color, RAPID in bold
```

**Plus color (`/plus`):**
- `/plus` opens a GUI showing a preview of your prefix in each color. The choice is stored in the player's profile in `rapid-core`, synced network-wide over Redis, and rendered in chat, tab, nametags and the sidebar through a placeholder (`%rapid_plus_color%`).
- **Colors are drawn from the 16 named chat colors**, so each one has a clear name in `/plus`. The palette widens with tier:

| Tier | Available plus colors |
|---|---|
| Rapid+ | White, Yellow, Gold, Aqua, Green, Light Purple (6) |
| Rapid++ | + Blue, Dark Blue, Dark Aqua, Dark Green, Dark Purple (11) |
| Rapid+++ | + Gray, Dark Gray, Black (all 14 non-reserved colors), plus **gradients** |

- **Rapid+++ gradients** (each with its own name in `/plus`, shown on their own row): Sunset (yellow → gold → pink), Ocean (aqua → dark blue), Aurora (green → aqua → pink), Frost (white → aqua), Citrus (yellow → green), Twilight (blue → pink), Silver (white → dark gray). `+++` is three characters, so a gradient's first, middle and last colors are what shows; none of them may be close to red.
- **Red and Dark Red are reserved** for staff prefixes, to prevent impersonation.
- Extra plus colors and gradients can be earned as achievement or Chapter rewards, but only players with an active rank can equip them.
- If a rank lapses, the color is remembered and restored when the rank returns.

| | **Rapid+** | **Rapid++** | **Rapid+++** |
|---|---|---|---|
| Price (subscription) | $4.99/mo | $9.99/mo | $19.99/mo |
| Bolts pass (30 days) | 1,000 ⚡ | 2,000 ⚡ | 4,000 ⚡ |
| Prefix | `RAPID+` | `RAPID++` | **`RAPID+++`** |
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
| SkyRealms | Island name hologram color | + ambient island particles | + animated island title, extra warp-sign styles |
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
- Packages run commands such as `grant <uuid> rapidplusplus 30d Store purchase txn:<txn>` and `rapid bolts add <uuid> 1100 <txn>`.
- Every grant is **idempotent per Tebex transaction ID** and logged (`grants`, `bolts_ledger`).
- Ranks use LuckPerms **temporary parents**, written by `/grant` through the LuckPerms API. Stacking extends time, and the highest active tier wins for display.
- Chargebacks trigger an automatic revoke plus a flag for staff review.
- `/store` and `/buy` in-game print a clickable `store.rapidskies.us` link.

---

## 7. Network-wide retention systems

- **Network level:** XP from both modes; level rewards are Bolts and cosmetics.
- **Quests:** 3 daily (banked) and 2 weekly, alternating between modes to encourage trying both.
- **Season Pass** (about 8 weeks, aligned with Chapters): free track plus a premium track bought with Bolts. Cosmetics only.
- **Leaderboards:** hub holograms and `/lb` for island value, town size, Trade levels, Chapter completion, and parkour times.
- **Social:** parties, friends, cross-server messaging, friend join notifications, "join friend" from the friends GUI.
- **Hub parkour** with a time leaderboard and cosmetic rewards. Every run is a freshly generated course of a fixed length (checkpoints at set distances), built in its own lane of a void world, so the hub map needs no course and no two runs are the same.
- **Events:** Expeditions, Skyborne fortresses, Frontier openings, End dragon fights, build contests (judged on the build server, cosmetic prizes).
- **Discord:** account linking (a one-time Bolts reward), report and punishment feeds, event announcements, a Frontier rotation countdown.

---

## 8. Compliance checklist (Minecraft EULA & Usage Guidelines)

Before launch, re-read the current [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines) (server/commercial section) and confirm each item:

- [x] Nothing bought with money or Bolts gives a gameplay advantage (§6.1 "not sold" list is enforced in code: shop items are tagged `COSMETIC` or `CONVENIENCE`, and the shop refuses anything else). *Phase 5 review:* the Bolt shop can only sell catalog cosmetics (tag `COSMETIC`), 30-day rank passes (`CONVENIENCE`) and the Season Pass premium track (`COSMETIC`); `ShopRepository.Tag` has no other value, the catalog refuses any other tag, and `CatalogTest` checks every sold item. Rank perks are prefixes, plus colors, cosmetic sets, hub trail/flight/pets, party and friend list sizes, queue priority, `/nick`, chat emotes and the Bolts stipend, which itself only buys the same things. Kits, items, claim blocks, homes (3 for everyone), island size, multipliers and cooldowns are not sold anywhere.
- [x] No paid randomized rewards. *Phase 5 review:* nothing random is sold for money or Bolts; the Featured Shop rotation is a fixed schedule, not a draw, and shows every item's price.
- [x] Bolts are non-tradable and can't be converted into Coins or Crowns. *Phase 5 review:* there is no Bolts transfer command (`/pay` and `/eco` only move the gamemode currency), and the ledgers are separate tables.
- [ ] The store at `store.rapidskies.us` clearly states "cosmetic only" and doesn't use Mojang/Microsoft branding.
- [ ] The server isn't presented as official or endorsed by Mojang.
- [ ] Tebex terms, refund policy and a privacy policy are published on `rapidskies.us`.
- [ ] Any perk later flagged as a gray area (for example, queue priority) is reviewed against the current guidelines. *Phase 5 note:* queue priority only reorders a full server's queue (everyone still gets in), and the Bolts stipend can only be spent on cosmetics and passes; re-read both against the guidelines' current text before launch.

---

## 9. Staff & moderation

**LuckPerms staff track:** Helper → Moderator → Sr. Moderator → Admin → Owner, with Builder and Developer as separate groups. Staff prefixes use the reserved red shades, a different one per role (Owner #AA0000 bold, Admin #E8212E, Developer #FF4F81, Sr. Mod #FF7A33, Moderator #FF5555, Helper #FFA0A0, Builder #B7410E; each still nearest to red or dark red), and staff chat shows the sender's role before their name.

**Grants** (`rapid-proxy`): staff give ranks, team roles and permissions with `/grant <player> <rank> <duration|permanent> [reason]` or `/grant <player> perm <node> ...`, never with LuckPerms commands. Every grant is a `grants` row (who, what, how long, why), shown with hover details by `/grants <player>` and revoked with `/grants revoke <id> [reason]`; LuckPerms holds the result, so TAB, chat prefixes and other plugins need nothing extra. Timed grants stack, and revoking one takes away only the time it had left. Nobody grants to themselves, team roles only go to roles below your own, and permissions only if you hold them.

**Staff mode** (`rapid-core`): vanish, freeze (with a chest-menu notice), inventory/ender chest inspect, teleport to player, random teleport to players, CPS/reach view from the anti-cheat, X-ray ore-ratio view, and a punishment GUI that shortcuts LiteBans templates.

**Survival-specific tools:** CoreProtect lookups and rollbacks, `/claimadmin` and island admin overrides, `/ah admin` and shop transaction lookups, and economy anomaly alerts for sudden large gains or suspicious auction patterns.

**Reports:** `/report <player> <reason>` creates a report with a chat snapshot and location. It's shown in a staff GUI and posted to Discord, with claim and resolve tracking.

**Logging:** chat, commands, private messages and economy transactions are logged to MariaDB with retention limits. `/history` is backed by LiteBans.

**Anti-cheat workflow:** alerts go to staff chat (filterable by severity), auto-punish only at high-confidence thresholds, and everything else is reviewed manually with replay of the logged violations.

**Punishment templates:** standardized durations for hacking, X-ray, duping, griefing, scamming, toxicity and advertising. Appeals go through `rapidskies.us` or Discord.

---

## 10. Data model (MariaDB, versioned migrations)

- `players` (uuid, name, first_join, last_join, last_server, last_seen, network_xp, locale, **plus_color**, tailwind_banked, login_streak, streak_day). `last_seen` is stamped by the proxy when a player leaves the network and cleared when the next login turns that absence into Tailwind; `login_streak` counts consecutive UTC days with a login, `streak_day` the last one (§6.2).
- `bolts_ledger` (id, uuid, delta, reason, source, txn_id UNIQUE, created_at), with `bolts_balance` as a cached view. Use a ledger, not a single balance column, for auditability.
- `mode_economy_ledger` (mode, uuid, delta, reason, created_at) for Coins and Crowns, kept separate per mode
- **Progression** (core migration V6): `cosmetics_owned` (uuid, cosmetic_id `<type>:<name>`, source: shop, `level:<n>`, `achievement:<id>`, `pass:<season>`, `chapter:<id>`, `season:<n>`, `admin:<name>`; rank sets aren't stored, they come with the rank); `cosmetics_equipped` (uuid, slot, cosmetic_id: kept while unusable, so a rank set comes back with the rank); `shop_purchases` (id, uuid, item, kind [COSMETIC/RANK_PASS/SEASON_PASS], tag [COSMETIC/CONVENIENCE], price, featured, txn_id, state [DELIVERED/PENDING], grant_id: a rank pass is PENDING until the proxy's `/grant`); `quests_progress` (uuid, period: a UTC day or ISO week, slot, kind, quest, progress, target copied at assignment, completed_at); `player_stats` (uuid, stat, value: lifetime objective totals for achievements); `achievements` (uuid, achievement, completed_at); `season_progress` (uuid, season, xp: network XP earned during the season, premium, premium_at). The catalog, quests and seasons themselves are files in the rapid-core jar (`cosmetics.yml`, `quests.yml`, `progression.yml`), not tables. `chapter_progress` is Horizons' (below)
- `horizons_trades` (uuid, trade, level, xp, active), `horizons_players` (uuid, pvp, pvp_changed_at, trades_changed_at, logout_frontier: the Frontier a player logged out in, so they come back at the spawn if it has closed), `horizons_trade_daily` (uuid, day, points: the Trade soft cap). Migration module `horizons`.
- **Claims** (migration module `claims`): `claims` (world, chunk_x, chunk_z, owner_type [PLAYER/TOWN], owner_id, flags bit set, claimed_by, claimed_at; PK world+chunk), `claim_trust` (owner_type, owner_id, uuid, role; the all-zero uuid is "everyone"), `claim_denied` (owner_type, owner_id, uuid), `claim_players` (uuid, purchased, bonus, ruins_since). Starter and playtime chunks aren't stored: they're computed from the config and the player's playtime on the server.
- **Towns:** `towns` (uuid, name, tag, tag_color, mayor, world, center_x, center_z, warp, upkeep_paid_until, state [ACTIVE/RUINS], ruins_since), `town_members` (uuid, town, role, joined_at), `town_roles` (town, role, permissions, land_role), `town_plots` (world, chunk_x, chunk_z, town, resident), `town_projects` (town, project, state, started_at, completed_at), `town_project_contributions` (town, project, uuid, resource, amount), `town_vaults` (town, contents). The **bank** is the town's row in `mode_balance` (mode `horizons`, keyed by the town's uuid), so deposits, upkeep and payouts are ordinary Crowns ledger entries; the **level** is the number of completed Projects.
- **Market** (migration module `market`): `market_listings` (id, token: the custody journal entry, mode, seller, seller_name, item_blob, item_name: the real English name, item_key: the price-history key, category, amount, price for the stack, fee, created_at, expires_at, status [ACTIVE/SOLD/EXPIRED/CANCELLED/REMOVED], buyer, buyer_name, sold_at, tax, flagged: sold far below average, closed_at, closed_by), `market_collection` (id, token UNIQUE: each way in happens once, mode, uuid, item_blob, item_name, amount, reason [BOUGHT/EXPIRED/CANCELLED/REMOVED/RETURNED], listing_id), `market_notices` (id, uuid, mode, kind [SOLD/EXPIRED/REMOVED], item_name, amount, price, proceeds, other_name: shown once, then deleted), `market_price_history` (mode, item_key, day, total, volume, sales: the average is total / volume)
- **SkyRealms** (migration module `skyrealms`; every `island` is a SuperiorSkyblock2 island uuid): `skyrealms_islands` (island, tier, tier_since: the Realm Tier; no row = the first), `skyrealms_tier_progress` (island, tier being unlocked, goal, progress), `skyrealms_shop_daily` (uuid, day, earned: full-price shop sales per UTC day, for the soft cap), `skyrealms_trade_routes` (id, seller_island, buyer_island, seller_name, buyer_name, item, qty, price, interval_hours, state [OFFERED/ACTIVE/ENDED], offered_by, offered_side, chest_world/x/y/z: the contract chest, next_due, deliveries, missed, created_at, signed_at, ended_at, end_reason), `skyrealms_route_deliveries` (route_id, cycle: the key, so each delivery happens once; qty, price, tax, delivered_at), `skyrealms_route_depot` (island, item, amount: delivered or returned goods waiting), `skyrealms_expeditions` (id, theme, started_at, ended_at, contributors, coins_paid, heart_broken), `skyrealms_seasons` (id, started_at, ends_at, ended_at), `skyrealms_season_baseline` (season, island, worth: worth at the season's start, or 0 for islands created during it), `skyrealms_season_awards` (season, uuid, island, island_name, island_rank, gained, cosmetic, bolts: the cosmetics engine grants from here, like `chapter_completions`; Bolts are paid once with txn `season:<id>:<uuid>`)
- `frontier_history` (id, modifier, seed, chapter, state [PREPARING/OPEN/CLOSED], created_at, opened_at, closes_at, closed_at, end_opens_at, dragon_killed_at, archive_path): one row per Frontier; its worlds are `rapidskies:frontier_<id>` (+ `_nether`, `_end`), regenerated from the seed and modifier. `chapter_progress` (uuid, chapter, entry, progress, data, completed_at) and `chapter_completions` (uuid, chapter, cosmetic, completed_at: the cosmetics engine grants from here, and it keeps the Bolts reward from paying twice). Migration module `horizons` (V2)
- `friends` (two rows per friendship), `friend_requests`. Parties and the join queue are ephemeral and live in the proxy's memory: there is one proxy and a restart drops every player anyway. Move them to Redis (`party:<id>`, `queue:<target>`) if a second proxy is ever added.
- `essentials_homes` (scope, uuid, name, world, x, y, z, yaw, pitch; PK scope+uuid+name) and `essentials_warps` (scope, name, position, created_by): `scope` is the server group, so a Horizons home is only offered on Horizons; `world` is the world key. Migration module `essentials`
- `reports`, `staff_actions` (staff, action, target, server, detail: freezes, inspections, vanish and staff-mode toggles), `chat_log`
- `grants` (uuid, kind [rank/permission], value, granted_by, reason, txn_id UNIQUE, created_at, starts_at, expires_at, revoked_at/by/reason): the record behind `/grant`; LuckPerms holds the effective nodes
- `parkour_times` (course, period, uuid, time_ms, achieved_at; PK course+period+uuid): each player's best per hub parkour course, all time (`period` = `all`) and per ISO week (`2026-W46`, UTC), for the hub leaderboards and `/parkour top`
- `store_transactions` (txn_id: the Tebex transaction, the part before any `:`; uuid, package, status [DELIVERED/CHARGEBACK/REFUNDED], note, timestamps): written by the proxy for console deliveries with a transaction id and marked by `rapid chargeback`

SuperiorSkyblock2 keeps its own tables in the same MariaDB instance, under its own schema.

Redis (every key and channel is prefixed `rapid:`, since LuckPerms shares the instance): `server:<id>` heartbeat and state, `maintenance`, the sets `vanished` and `frozen` (staff state that survives relogs and server switches), `shared-ip` (written by the proxy: accounts sharing an IP with one that joined earlier, which earn no activity Bolts), and `playtime:<day>:<uuid>` active-second counters; plus pub/sub channels for profile and plus-color updates, Bolts changes, maintenance, connect requests from backends to the proxy (`connect`), `vanish`, `freeze`, `mode-balance` (a Coins/Crowns balance changed on another server), `broadcast` (an admin's `/broadcast`, shown by every backend), `claims` (a claim, trust list or town changed: other Horizons instances reload it), `market` (an auction house notice for a player: the server they're on shows it), `cosmetics` (cosmetics were granted to a player on another server: the one they're on reloads and names them), `rank-pass` (a rank pass was bought with Bolts: the proxy grants pending ones, and checks every minute anyway) and `staff-alert` (an automatic alert, such as rapid-market's suspicious sales, shown in staff chat by the proxy). Private messages, party chat and staff chat run on the single proxy and need no channel; `presence:<uuid>` and per-party keys join them only with a second proxy.

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
| **4b. SkyRealms** | 8–17 | SuperiorSkyblock2 setup and island schematics, Realm Tiers 1–3, Expeditions (3 themes), Trade Routes, server shop |
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
- [x] `/hub` (alias `/lobby`): `/play` for the lobby group; party members may use it to go back alone
- [x] Grants (§9): `/grant`, `/grants`, `/grants revoke` on the proxy, applied through the LuckPerms API with a Redis push to the backends; stacking timed grants, store idempotency via `txn:<id>`, team-role and permission limits (`grants`, core V4)
- [x] `/plus` gradients for Rapid+++ (§6.1): seven named gradients on their own menu row, also settable with `/plus <name>`; rendered per character in chat, tab and nametags through `%rapid_prefix%`
- [x] Lang bundles use what 1.20+ clients render: hover hints on every button, `<key:...>` keybinds in staff tools and the freeze notice, clickable command hints; a test fails if a `<hover>`, `<click>` or `<key>` tag doesn't parse

Next:

- [x] Check on 1.20.1 and 26.3: LiteBans mutes block `/msg`, `/r` and `/pc`; party follow and the queue action bar; `/plus` menu items and lore; staff mode tools (keybind names in item names and lore), the freeze notice and title; `RAPID+++` nametags; vanished staff missing from the tab list; button hovers; `/grant` a rank to an online player and see the prefix change on their backend without a relog
- [ ] Tune in beta: Tailwind rate and starter bank, playtime Bolts, AFK timeout
- [ ] Later staff tooling from §9 that needs other plugins' APIs: CPS/reach view (Grim), X-ray ore ratios, LiteBans punishment GUI

### Phase 2 progress

- [x] Hub and spawn worlds imported (`worlds/`): Free Lobby as the hub, the Trading Post as the SkyRealms spawn, the CottageCore Lobby as Horizons' `rapidskies:spawn` world beside the Heartland. `rsdeploy.py worlds` ships their chunks and entities into the Paper 26.1+ layout (`<level>/dimensions/...`), never a level.dat, and only replaces an existing world with `--replace` (old one kept in `world-backups/`)
- [x] `rapid-core` spawn and world management: spawn point per server (hub on every join, gamemodes on first join and bedless deaths), void rescue under the sky islands, per-world game rules / time / weather / border from the profiles, protected spawn builds (`rapid.world.build`), `/spawn`, `/setspawn`. A test checks every game rule named in the profiles exists in Paper 26.3
- [x] First boot of each world: walk the builds for blocks the upgrade got wrong (the hub's chunks are from 1.8), and check the spawn facing on 1.20.1 and 26.3
- [x] Spawns re-checked against in-game screenshots and the world data. Hub: moved from the courtyard (which faced away from the gate) to the stone-slab marker on the south terrace, `-5.5 87.5 9.5` facing north over the courtyard to the stage and gate hall. SkyRealms: the compass rose's true centre is `28.5 150 -29.5` (block -30 spans -30..-29; the old `-30.5` was a block off), now facing west to the pavilion. Horizons: unchanged, `0.5 74.1 0.5` facing north up the path to the fountain
- [x] A way from the Horizons spawn to the Heartland: `/rtp` (aliases `/wild`, `/heartland`) and an optional exit region at the spawn (rapid-horizons, phase 3b; set `rtp.portal.region` once the arch is picked in-game)
- [x] SuperiorSkyblock2's `spawn.location` set to the Trading Post spawn (phase 4b: `skyrealms_spawn, 28.5, 150, -29.5, 90, 0`)
- [x] `rapid-hub`: game menu (hotbar compass and `/games`) with live player counts per mode from the server heartbeats; Citizens NPCs for SkyRealms and Horizons on the stage's inner slab pedestals (centre pedestal kept for gamemode 3), in an in-memory registry so Citizens never saves them, with text-display labels showing the live count; clicking either joins through the proxy queue (`RapidCore#connect`). Hotbar items are locked in place and never touch staff mode's tools
- [x] Double jump for everyone (re-armed on landing), a particle trail for `rapid.hub.trail` (Rapid+) and `/fly` for `rapid.hub.fly` (Rapid++); player visibility toggle (per viewer, remembered, independent of vanish)
- [x] Parkour engine: courses from config (start, ordered checkpoints, finish, `fail-y`), runs end on flying, teleports, gamemode or world changes; action-bar timer, back-to-checkpoint / restart / leave hotbar, `/parkour [leave|checkpoint|restart|top [weekly]]`. Bests all time and per week in `parkour_times` (core V5); a new #1 is announced to the hub
- [x] Leaderboards: all-time and this-week holograms over the courtyard's floor emblems, redrawn every minute and after each finish; "opening soon" until the course exists. `/rapidhub here` prints copyable positions, `/rapidhub reload` rebuilds NPCs, boards and courses
- [x] Parkour is generated instead of built: each run gets its own random course (`CourseGenerator`: single-block jumps any 1.20+ player can make, a gold checkpoint platform every `checkpoint-every` blocks, a finish platform at `length`) in a lane of the `rapidskies:parkour` void world (rapid-core creates it on the hub). The clock starts when the player jumps off the start platform; falls go back to the last checkpoint; blocks are removed when the run ends, and lanes in use are written to `parkour-lanes.txt` so a crash never leaves a course behind. Started from the hotbar feather, `/parkour start [course]` or an optional entrance block in the hub. Course `main`: 150 blocks, checkpoints at 50 and 100
- [x] Sidebars per server (TAB on the proxy, `velocity/plugins/tab/config.yml.patch`): hub (rank, Bolts, players per gamemode, network count), SkyRealms (rank, Bolts, Tailwind, Coins, players), Horizons (rank, Bolts, Tailwind, Crowns, world, players), and a plain one for build/staging; `/sb` toggles it. New placeholders: `%rapid_rank_name%`, `%rapid_bolts%` (rapid-core's `BoltsCache`, re-read once a minute), `%rapid_tailwind_status%`, `%rapid_balance_status%`, and rapid-hub's `%rapidhub_players_<group>%`
- [x] Rank and team prefixes without brackets (`RAPID++`, `MOD`), in chat, tab, nametags and `/grants`
- [x] Build mode (`rapid-core`, every backend): the protected hub and spawn worlds can't be changed by anyone, staff included, unless they switch on `/build` (`rapid.world.build`: builders, developers, admins). It puts them in creative with their inventory, game mode and flight set aside in persistent data (shared with staff mode, so a crash loses nothing), shows a boss bar, and blocks drops, pickups and death drops so creative items never reach a survival economy; it ends on its own on leaving the protected worlds or the server. Staff with the permission get an action-bar hint when they try to build without it. Vehicles in the builds are protected too
- [x] Sidebar World line uses display names (`%rapid_world%`, rapid-core `world-names`: Spawn, Heartland, The Nether, The End; unlisted worlds get a readable form of their key) instead of TAB's `%world%` folder name
- [x] SkyRealms sidebar (phase 4b): island level (`%superior_island_level%`), realm, season place and next Expedition (`%rapidskyrealms_tier%`, `_season_rank%`, `_expedition%`). Worth was left off: the level says the same and the sidebar was already long
- [ ] Optional: an entrance pressure plate for the parkour in the courtyard (`parkour.courses.main.entrance`, from `/rapidhub here`)
- [x] Check on 1.20.1 and 26.3: NPC skins and that their nameplates stay hidden alongside TAB's nametags, label and leaderboard readability from the spawn, game menu counts and joining, hotbar items can't be moved or dropped, double jump and `/fly`, visibility toggle with a vanished staff member online, a full parkour run including a new record (jumps feel fair on 1.20.1 too, blocks are gone afterwards), and every sidebar (lines fit, values fill in, 1.20.1's red numbers don't clash)
- [x] Pick NPC skins (`modes.<id>.skin-texture` / `skin-signature` from mineskin.org, so they never change)

### Phase 3 progress

**3a. Claims & towns (`rapid-claims`)**: code complete, with 187 tests. 178 are unit tests (the protection matrix row by row, role resolution, plots, borders, contiguity, upkeep and ruins, settings, lang); 9 run every repository statement against MariaDB:

- [x] Chunk claims in the Heartland only, held in memory (`LandIndex`, O(1) lookups by chunk) and written through to MariaDB. The server shuts down if the claims can't be loaded, rather than run unprotected
- [x] Allowance: 9 starter chunks, one per 2 h played on the server (up to 40), chunks from other plugins (`ClaimService.registerAllowance`: Trade levels), and chunks bought with Crowns at a rising price (`/claim buy`, 500 + 250 × n). Never money or Bolts
- [x] Trust roles (Visitor → Trusted → Member → Builder → Co-owner) per owner, with `*` for everyone. Co-owners manage trust and flags but can't make Co-owners. Entry denial per player covers walking, pearls, chorus fruit and teleports
- [x] Flags per chunk (PvP, monster spawning, fire spread, leaf decay; explosions are always blocked), set for one chunk or all the owner's land from `/claim flags`
- [x] Protection suite:
  - blocks, buckets and trampling
  - containers, doors, redstone settings, item frames, armor stands, lecterns, beds and signs (shop signs stay clickable)
  - animals, villagers, displays and vehicles
  - explosions of every kind
  - fluids, pistons, dispensers and hoppers across borders
  - fire spread and burning; trees, mushrooms, sculk and bone meal growing across borders; sponges
  - portals; projectiles (buttons, pots, TNT, candles); splash and lingering potions; mob griefing
  - PvP in claims with the flag off
- [x] `/claim map`: a 6-row chest map (9×5 chunks, north up). Click to claim, right-click to unclaim; buttons pan the map, switch between personal and town claiming, and open borders and flags. `/claim show` draws borders with particles plus a client-side ground outline (gold yours, diamond your town, iron trusted, redstone others). Crossing into other land shows "Entering …" on the action bar
- [x] Towns:
  - founding costs 5,000 Crowns (first day of upkeep included), on wilderness or your own chunk
  - roles Mayor → Assistant → Resident → Recruit, with per-role permissions and land access (`/town perms`); invites, kicks, ranks, mayor hand-over
  - contiguous town land from the center chunk, capped at 16 + 8 × members + Project chunks
  - plots handed to residents: their own trust list applies, and the mayor only gets doors
  - town trust and denials; a tag and color (no reds) in chat; `/tc` town chat
  - rename (paid from the bank); disband (bank paid to the mayor)
- [x] Town bank in the Crowns ledger: deposits and withdrawals are one-transaction transfers (`Ledger.transfer`, new in rapid-common). Daily upkeep (50 + 10 × chunks) is charged with a per-day transaction id so it's never taken twice, on one worker thread and under a MariaDB lock
- [x] Ruins: towns 14 days behind on upkeep, and the personal land of players away 90 days, become ruins. Ruins stay protected 7 more days, then are released (builds stay). Paying (towns) or logging in (players) restores them
- [x] Town Projects from a config catalog, 5 to start: Lighthouse → warp, Storehouse → vault, Town Walls → +16 chunks, Market Square → market stall, Banner Hall → banner and title. One runs at a time; members donate items and Crowns from a chest menu, and completion raises the town level and applies the unlocks (`/town warp`, the shared `/town vault`)
- [x] `/claimadmin` (bypass, info, unclaim, unclaimall, transfer, restore, allowance, deletetown), every change logged to `staff_actions`
- [x] Integrations:
  - the `ClaimService` API (ServicesManager) for other plugins
  - PlaceholderAPI `%rapidclaims_town%`, `_town_tag%`, `_here%` and `_chunks%` for the Horizons sidebar
  - town tags in chat through rapid-core's new `ChatTags`
  - QuickShop-Hikari shops only inside claims where the creator is a Member
  - the Redis `claims` channel, so a second Horizons instance stays in step
- [x] Deploy: the `smp` plugin set (rapid-claims, rapid-horizons, QuickShop-Hikari), CoreProtect in `survival`, the QuickShop patch, the Horizons sidebar lines, and LuckPerms lines for `/claimadmin` and entry exemption

**3b. Horizons SMP (`rapid-horizons`)**: code complete, with 75 tests (4 against MariaDB):

- [x] `/rtp` into the Heartland: a random safe surface spot inside the border, never in or next to a claimed chunk, with async chunk loading and a cooldown; plus an optional exit region at the spawn
- [x] The Heartland portal at the spawn: an arch across the path south of the plaza (opening `-2 72 12`..`2 76 12`, facing the spawn), built by rapid-horizons from its config on every start (mossy stone brick columns, spruce lintel, flowering azalea and lanterns, nether portal fill). Physics can't break the frameless portal blocks, and vanilla portal travel from them is cancelled (plus `allow_entering_nether_using_portals: false` in the spawn world). A "The Heartland" text-display hologram floats in front of it (`rtp.portal.hologram`, lang `travel.portal-hologram`), respawned on start and `/horizons reload`
- [x] Restart kicks name the gamemode ("Horizons SMP is restarting", "SkyRealms is restarting"): `settings.shutdown-message` in the smp and skyrealms profiles' `bukkit.yml.patch`, over paper-base's "RapidSkies is restarting"
- [x] Back in line after a restart: a stopping backend (rapid-core's heartbeat, only on a real shutdown) publishes Redis `rapid:server-stopping` with everyone on it before Paper kicks them. The proxy queues them for that exact server ahead of new arrivals (lang `queue.returning`), holds the queue off the old instance until a heartbeat with a later start shows up, and drops them from the line after `queue.return-after-restart-minutes` (default 5; 0 turns it off). Lobbies and staff servers are left out
- [x] `/back`: the spot before the last command or plugin teleport, or the death spot (`back.on-death`); never a void fall, lands on the nearest safe footing in the same column or refuses, not while combat-tagged, 10 s cooldown
- [x] Citizens NPCs on the plaza's four pedestals, rebuilt from the config each start with text-display labels (`npcs.<id>`: `rtp`, `trades`, `server:<group>` or `command:<command>`): Heartland Guide, Land Surveyor (`/claim help`), Trade Master, Lobby. `/horizons here` prints positions; `/horizons reload` rebuilds the NPCs and the arch. `Labels` moved from rapid-hub to rapid-core so both use it
- [x] Opt-in PvP (`/pvp`): off by default, with a 5-minute toggle cooldown and a 30 s combat tag; claims' PvP flag decides inside claims. Covers melee, projectiles, potions, clouds, pets, TNT, crystals, fire and fishing rods, with a hook for Wildlands
- [x] Combat logging (`pvp.combat-log`): leaving while combat-tagged (quitting, timing out, or switching server through the proxy) kills the player where they stood, so their items drop there and the kill goes to their last opponent. A "logged out in combat" death message, optional console `commands` (`<player>`, `<uuid>`, `<attacker>`), and a notice on their next join. Restarts and server-side errors never count, kicks only with `punish-kicks`, and `rapid.horizons.combatlog.bypass` is exempt. Tagged players get a warning, an action-bar countdown and an "out of combat" line; the spawn's `server:` NPCs refuse them; dying ends their own tag
- [x] Trades: Miner, Farmer, Hunter, Builder, Enchanter and Fisher, up to 2 at once with a switching cooldown
  - XP and Crowns per action, with anti-abuse: placed ores and crops don't pay, spawner mobs pay less, nothing while AFK
  - a daily soft cap with diminishing returns, and Tailwind
  - Crowns paid in batches off the main thread
  - the `/trades` menu, and claim chunks from Trade levels
- [x] Trade payouts survive a database failure (was the known gap): each player's unsaved work waits in an outbox, a batch the database refuses goes back and is retried every `payout-seconds`, and every payout carries a ledger transaction id fixed when it was earned, so a retry after a lost commit is a duplicate, never a second payment. Still unpaid at shutdown: `pending-trades.json`, queued again on the next start. A login reads unsaved progress over the database's, so XP never goes backwards. `/frontier status` shows Crowns waiting
- [x] Trade side-grade perks (§5.2), soft-capped as `max × level / (level + 20)` (half at level 20, never all): Miner, Haste after natural ores; Farmer, ripe crops replant from their own seed; Hunter, more vanilla XP (not from spawners); Builder, less fall damage; Enchanter, chance to keep the lapis; Fisher, shorter bite waits (never under a second). Only while the Trade is active and the player isn't AFK; shown in `/trades`
- [x] The Heartland is 10,000 × 10,000 (border ±5,000 from rapid-core's smp profile; `/rtp` lands inside it); pre-generation is a one-time Chunky run (infra/README.md → The Heartland)
- [x] Frontier rotation (`/frontier`, `/frontier go`): one open Frontier at a time, 30 days, with a network warning 7 days out while the next one is created and pre-generated through Chunky's API (softdepend). At closing time everyone inside goes to the spawn and the worlds are unloaded, archived to `frontier-archive/` and deleted after 30 days. The database changes first, so a failed write never leaves two open. Players who logged out in a closed Frontier log in at the spawn and are told why. The first start opens #1. Admin: `status`, `prepare [modifier]`, `rotate confirm`, `extend <days>`, `openend`
- [x] 2 launch modifiers, both vanilla terrain with the modifier's biomes picked from vanilla's climate parameters (oceans, rivers, beaches and the deep dark stay; every biome exists in 1.20):
  - Overgrowth: forest-family biomes, 3 extra trees per chunk, 30% of natural hostile spawns swapped for husks, strays, witches, cave spiders or zombie villagers
  - Drought: desert, badlands and savanna (dry river valleys); no rain; bucket water and melting or broken ice boil away (cauldrons still work); hunger drains faster bare-headed under the midday sun
- [x] Wildlands: the Frontier's outer ring (2,000+ blocks out, square like the border) with forced PvP (`PvpService#setWildlands`), double drops from natural ores (50%) and hostile mobs (50%), +25% Trade income; a title and a red boss bar while inside
- [x] Nether/End rotation: every Nether portal in the Heartland or Frontier leads to the current Frontier's Nether (Paper's `EntityPortalReadyEvent`, so scaling and exit portals are vanilla), and back to the overworld each player came from; End portals lead to the current End once it opens, a day after its Frontier, with a network-announced community dragon fight (rewards once per Frontier to everyone with 10+ damage: 500 Crowns, 20 Bolts, XP bottles)
- [x] Skyborne events (`/skyborne`, `/skyborne go`): every ~4 h (±30 min, 3+ players on), a fortress over the Frontier's safe centre, announced network-wide 10 minutes ahead with coordinates. One design, the Gilded Bastion (blackstone and gold island, walled courtyard, corner towers, landing pad), built over a few ticks only into air and taken down afterwards; an updraft of clouds lifts players onto it. Three waves then the Sky Warden, all scaled to the players fighting; everyone with 20+ damage gets 400 Crowns, 15 Bolts (EarnGuard, 20/day) and items. Blocks can't be broken, burnt or blown up; falling off gives Slow Falling; a journal removes a fortress left by a crash
- [x] Chapter 1, "The First Horizon" (`/chapter`): a 12-entry collection log (first steps, 2,500 blocks out, 6 biomes, the Wildlands, 5 diamond ores, an emerald ore, 3 witches, a pufferfish, the Nether, 2 ancient debris, a Skyborne win, the dragon), counted only in that month's Frontier, its Nether and End. Finishing records the `title:pathfinder` cosmetic in `chapter_completions` for the cosmetics engine and pays 100 Bolts once. Chapters are assigned to Frontiers in order from `frontier.chapters`
- [x] Network announcements reuse the Redis `broadcast` channel, so the hub hears about fortresses, new Frontiers and the dragon too
- [x] Frontier worlds through Multiverse-Core (softdepend; `WorldHost`, Bukkit fallback without it): rapid-horizons registers as an MV generator plugin, so every Frontier overworld is created with `rapid-horizons:<modifier>` and MV remembers the generator; Frontier worlds are `auto-load: false` (the rotation loads them) and are removed from MV before archiving; Frontiers from before MV are imported with their generator. The Heartland is imported as it is (pre-generated vanilla terrain, never regenerated). Startup warns about MV settings that would override rapid-core (smp profile `plugins/Multiverse-Core` turns them off). `/mv create <name> normal -g rapid-horizons:<modifier>` previews any modifier's terrain
- [x] Custom terrain per modifier (`frontier.terrain`, every block and biome in 1.20), plus 2 new modifiers in the rotation (`overgrowth, drought, shattered-skies, deep-dark`):
  - Overgrowth: forest floor turned to moss, podzol and rooted dirt in patches; ferns, leafy bushes and azaleas between the extra trees
  - Drought: seas, rivers and aquifers drained to y 48, after vanilla's carvers too (deep warm pools in the old basins, beached shipwrecks, dry riverbeds, no monuments to spill), grass cracked to coarse dirt in patches, dead bushes on the seabed
  - Shattered Skies: our own terrain, floating islands at two heights (~y 84 and ~y 156) with hanging, cracked undersides over a sea at y 24 (a fall ends in water); vanilla decorates them per biome (meadows, groves, cherry, light woods); 4 veins per chunk of diamond, emerald, gold, lapis or redstone in the islands' stone; springs that would pour off an island are sealed; no structures
  - Deep Dark: barren surface (plains, gravelly hills, half the grass worn away) over caverns 50–120 blocks across between y -48 and 8 (35% open, stone pillars, a 20-block roof, never opened onto water or lava, chunk edges included); the deep dark biome spreads through them (sculk, shriekers, ancient cities), lush pockets where it's wet; ores below y 0 drop double 25% of the time
- [x] `/rtp` refuses while combat-tagged (it was a way out of a fight), like the other teleports

Next:

- [x] Check on 1.20.1 and 26.3:
  - `/claim map` (pane names and lore, clicks) and borders (particles and outline)
  - every protection row by hand on a test claim
  - a town from founding to a finished Project, and the vault with two members
  - upkeep and ruins, with short test values on staging
  - town tags in chat, and the sidebar's Town and Land lines
  - a QuickShop shop inside and outside a claim
  - `/rtp` and `/pvp`
- [x] Check in-game on 1.20.1 and 26.3: the portal arch (looks right in the path, the portal blocks stay, walking in lands in the Heartland and never the Nether), the four NPCs (labels readable from the spawn, skins, clicks), `/back` after `/rtp`, `/spawn` and a death, and `/build` on the hub and the Horizons spawn (creative, boss bar, can't drop, ends on `/rtp`)
- [x] Check in-game: combat logging. Tag two players, then quit (one dies at the spot, items drop, the death message names the opponent, the notice shows on rejoin and the respawn works); `/hub` while tagged does the same; a `/kick` doesn't kill; restart `smp-1` mid-fight and nobody dies; the action-bar countdown and "out of combat" line on 1.20.1 and 26.3
- [x] Check in-game: restart `smp-1` with players on it; they land in a lobby with the `queue.returning` line and the action bar, go back once it's up, and a party follows its leader back
- [x] NPC skins for the four spawn NPCs (`npcs.<id>.skin-texture` / `skin-signature` from mineskin.org)
- [x] Pick the Heartland's pre-generated radius: 10,000 × 10,000
- [x] Rest of phase 3b: Frontier rotation and 2 launch modifiers, Wildlands, Skyborne events (1 fortress), Chapter 1, Trade side-grade perks, Nether/End rotation
- [x] Pre-generate the Heartland once with Chunky (infra/README.md → The Heartland)
- [x] Check on staging, then 1.20.1 and 26.3: the first start opens Frontier #1 (Overgrowth); `/frontier` and `/frontier go`; walk out into the Wildlands (title, boss bar, PvP with `/pvp` off, double ore drops); a Nether portal from the Heartland and from the Frontier and back; End portal refused, then `/frontier openend` and kill the dragon with two players (both rewarded once); `/frontier prepare drought` then `/frontier rotate confirm` with players inside (sent to the spawn, the old worlds archived, a player who logged out inside comes back at the spawn); Drought: no rain, water boils, the sun message; check both modifiers' terrain looks right and biomes render on 1.20.1
- [x] Check Skyborne with `/skyborne start 1` and 1–3 players: the network announcement on the hub, `/skyborne go`, the updraft onto the pad, waves and the Sky Warden's boss bar, rewards, Slow Falling when it drifts away, the blocks gone afterwards; restart mid-event and see the fortress removed
- [x] Check Chapter 1 entries tick and `/chapter` renders (icons, progress, the reward slot); the perks in `/trades` (the Farmer replant and the Enchanter lapis especially); stop MariaDB on staging during play and see Trade Crowns paid once it's back, and after a restart through `pending-trades.json`
- [ ] Tune in beta: Frontier and Wildlands sizes, modifier strengths, Skyborne interval and scaling, event rewards against the Crowns economy
- [x] Check on staging, then 1.20.1 and 26.3: after the first start and `rsdeploy.py apply` (Multiverse's profile), no MV warning at startup; `/mv list` shows the Heartland and the open Frontier with its `rapid-horizons:<modifier>` generator; `/mv create` previews of `shattered_skies` and `deep_dark` (islands, sea, ores; caverns, sculk, roof intact); `/frontier prepare shattered-skies` then `rotate confirm` (Chunky pre-generates it; `/frontier go` lands on an island; a Skyborne fortress sits above the islands); a death in a Frontier respawns at the Horizons spawn; `/build` still creative; the spawn keeps PvP off; then the same rotation into Deep Dark (the ore bonus action bar below y 0)
- [ ] Later: anti-xray hidden blocks for the rotating Nether (Paper only takes per-world lists from the world's own folder); more fortress designs (§12: a variant a month)
- [ ] Later, once there's a second Horizons instance: town chat across instances (Redis) and locking the town vault to one server. Also the town tag before names in the tab list on Horizons only (TAB per-server group settings)
- [x] Known gap fixed: a database failure during a Trade payout used to drop that batch's Crowns; they're now held and retried (see above)

### Phase 4 progress

**4a. Auction house (`rapid-market`)**: code complete, with 49 tests. 27 are unit tests (categories, fees and tax rounding, prices, real names, the alert rule, settings, lang, the custody journal's recovery); 22 run against MariaDB, including the §11 list: parallel buyers on one listing, a crash at each step of listing and buying, and buying with a full inventory:

- [x] One plugin on both gamemodes (the `survival` plugin set). Each server trades in its own currency from rapid-core's `server.gamemode`; every table is scoped by mode, so a SkyRealms server can't see or buy a Horizons listing. It turns itself off where there's no gamemode currency
- [x] `/ah sell <price> [12h|24h|48h]` opens a sell menu first: the item as buyers will see it, the 2% fee, the 5% tax and what's left, the 7-day average price, and a button per duration. Nothing leaves the inventory until Confirm, and the held item must still match. Prices take `1,500` / `2.5k` / `1m`, whole only; refused in staff mode, build mode, creative and spectator; `listing.blocked-items`
- [x] Listing cap: 10 for everyone, plus listings from other plugins through `MarketService.registerListingBonus` (rapid-horizons: one per 10 total Trade levels, up to +10; SkyRealms island level in 4b). The fee, the cap check and the insert are one transaction under a lock on the seller's balance row, so parallel listings can't pass the cap
- [x] Browse menu (6 rows): 36 listings a page, six categories plus All, sort (newest, cheapest or dearest per item, ending soon), search by item, item key or seller through a chat prompt (rapid-core's new `ChatPrompts`, never an anvil), your listings, your collection bin, refresh. Lore shows the real item name, the price (and per item for stacks), the seller and the time left
- [x] Buying is one transaction: the conditional `ACTIVE → SOLD` (exactly one row, or "too late"), the buyer's guarded debit, the seller's credit less tax, the item into the buyer's bin, the price history and the seller's notice. Balances are locked in uuid order first, so crosswise purchases can't deadlock. The buy menu shows the 7-day average
- [x] Delivery always goes through the collection bin: the item lands there in the sale's transaction and is moved into the inventory straight away if it fits (`/ah collect` to collect one, or everything that fits, later). Expired, cancelled and staff-removed listings go there too; nothing is ever dropped
- [x] Custody journal (`plugins/rapid-market/custody/`): an item moving between an inventory and the database is written to disk first and the player's data saved, so a crash at any point ends with the item in exactly one place (a listing with no committed row goes to the bin; a collected row that never reached the inventory is put back). Settled on start and every 30 s; a unique token per way into a bin makes recovery idempotent, and a second move of the same item waits until the first is settled. The one window left is between two consecutive main-thread statements (the player-data save and the journal delete)
- [x] Every money move is a `mode_economy_ledger` row (`market:fee`, `market:buy`, `market:sale`, each with a transaction id), done inside the market's own transaction through new `Ledger` in-transaction methods; rapid-core's cached balances and the `mode-balance` channel are updated (`ModeEconomy#changed`)
- [x] Notices: a sale, expiry or removal notifies the seller once, on whatever gamemode server they're on (Redis `market`), or at their next login there, with a reminder of items waiting in the bin
- [x] `/ah admin` (moderators): browse as staff and take a listing down (back to the seller's bin, with a notice; logged to `staff_actions`), `player <name>` for one seller, `history <name> [page]` for sales and purchases, `remove <id>`
- [x] Suspicious-sale alerts: a sale under 25% of the item's 7-day average (items averaging 50+) is flagged; the 3rd flagged sale from one seller to one buyer within 7 days (and every 3rd after) goes to staff chat on every server through the proxy (Redis `staff-alert`)
- [x] Price history per item per UTC day (`market_price_history`): plain items by material, anything enchanted, named or damaged by material plus a hash of its data, so specials don't skew the plain price. The economy health dashboard can read the same table
- [x] Deploy: `dist/rapid-market.jar` in the `survival` plugin set; LuckPerms gives moderators `rapid.market.admin`

Next:

- [x] Check on 1.20.1 and 26.3, on Horizons and SkyRealms: `/ah sell` (menu, durations, the fee gone from `/balance`), browse every category and sort, search through chat, buy with room and with a full inventory (the bin and `/ah collect`), cancel, the cap message at 10 listings, an item newer than 1.20 (pale oak, a trial key) listed from 26.3 and browsed from 1.20.1 (the lore line shows its real name), the seller's notice while online on the other server and after a relog, `/ah admin` take-down and `history`, the staff-chat alert with test values (`alerts.repeat: 1`)
- [x] On staging, kill the server process (not `stop`) right after confirming a listing and right after collecting: after the restart the item is in exactly one place (the log shows `custody/` being settled)
- [ ] Tune in beta: fee and tax against the Crowns and Coins supply, listing durations, alert thresholds
- [x] Phase 4b: rapid-skyrealms registers an island-level listing bonus (+1 per 100 island levels, up to +10)
- [ ] Later (v2): bid-style auctions and buy orders (§13)

**4b. SkyRealms (SuperiorSkyblock2 + `rapid-skyrealms`)**: code complete, with 60 tests. 54 are unit tests (the three island layouts, how deep they hang, that nothing on them falls or pops off, and their schematic markers as SuperiorSkyblock2 reads them, the Trading Post NPCs' config, the three Expedition islands across 65 seeds: a simulated walk from the pad reaches the heart and every resource block, never ends in a pit with no way out, and nothing floats or leaks water; tier goals, shop pricing and the soft cap, route rules, heart-loot shares, season ranking, settings, lang); 6 run every repository statement against MariaDB, including parallel shop sales sharing one soft cap, an ascend that can't be paid or happen twice, and a route delivery that's unpaid, delivered, then retried:

- [x] SuperiorSkyblock2 set up (the `skyrealms` plugin set and profile): its own MariaDB schema, the island world `skyrealms_islands` (overworld only for now), the Trading Post as its spawn (rapid-core protects it; deaths respawn on the island), optional island names, three disbands, no previews. Its upgrades are re-priced in Coins (island size 50→150, team 4→10, hoppers, crop growth, spawner rates, mob drops), generated with their menu so the two agree, and **its generator upgrade is gone**: Realm Tiers own the generator. Its missions are off (Realm Tiers are the quest chain), and its own biome picker is disabled (biomes are tier unlocks). LuckPerms gives everyone `superior.island.*` on skyrealms; moderators get `/is admin` show, spy and bypass
- [x] Starter islands built in code (`IslandLayouts`): Meadow (a round oak on a rise, wildflowers, a mossy boulder, the classic east lobe), Cherry Grove (a wide drooping cherry with a second bough, petals everywhere) and Atoll (a vine-hung jungle tree, a sand beach that steps down at the rim, ferns and dead bushes), all an equal start (about the same soil and sand, a tree, coal, the starter chest from the config plus two saplings). Each is an irregular slab of soil over a ragged cone of stone, andesite and tuff that hangs about ten blocks deep, with hanging roots and glow lichen underneath; drawn from a fixed seed, so every paste is the same. Registered with SuperiorSkyblock2 as schematics `meadow`, `cherry` and `atoll` through its parser API from `onLoad` (a marker file per layout in its `schematics/` folder; an in-game schematic of the same name wins). No binary schematics to keep in step. The creation menu shows the three with plain item icons
- [x] Realm Tiers 1–3 (`/realm`): Meadow → Desert → Frost. Each island works on the next tier's goals (island level, generator blocks mined, crops harvested, Coins from the shop, fish, Expeditions, Trade Route deliveries), shared by every member and counted on the island only; placed blocks and replanted plants never count. When every goal is done, the leader (or an Admin role) ascends for Coins (5,000 and 25,000), in one transaction with the tier change. A tier sets the island's generator odds (Desert adds iron and gold; Frost lapis, redstone, diamond and emerald), unlocks its biome for `/realm biome` (desert: cactus and rabbits; snowy plains: water freezes, a renewable ice source) and its shop category, and gives reward items once. Progress is counted in memory and written every 30 s
- [x] Server shop (`/shop`, `/sell hand|all`): seven categories, two opened by tiers; buy basics, sell what the island makes. Only plain items sell, and selling never pays more than buying costs (the config is refused otherwise). §5.3 soft cap: the first 20,000 Coins of sales a day pay in full, the next 20,000 at 50%, the rest at 25%; Tailwind's bonus applies on top. A sale is one transaction with the day's total; items are taken first and given back if paying fails
- [x] Expeditions (`/expedition`, `/expedition go`): every ~3 h (±20 min, 2+ players), announced network-wide 5 minutes ahead, a themed island drifts into the void world `rapidskies:expeditions` for 15 minutes: **Crystal Caverns** (an open geode with light shafts, terraced crags with ore veins, amethyst spires, stalactites underneath), **Sunken Ship** (a beached wreck with a sterncastle cabin, two masts with torn sails and a crow's nest, a gangplank to the deck, a breach into the hold, tide pools, a reef and dead coral), **Overgrown Ruins** (mossy courtyard walls with corner towers and an arched gate, a fallen colonnade, a reflecting pool, an old oak splitting a corner, vines, wild melons and pumpkins, bee nests). Each is built from a seed, a little different every time. The heart is always on the ground in the middle, a clear walkway leading to it from the landing pad, and it glows: a slightly larger, fully lit copy (an item display, so the chest shows too) outlined in the theme's colour through walls, a see-through label with the strikes so far, and rising light. Only its resource blocks break, and their loot goes straight into the miner's inventory with contribution points (nothing on the ground to steal). The heart takes hits from everyone (12 per player there, at least 24); when it breaks, each striker gets its loot in proportion to their hits, so the last hit means nothing. When it leaves, contributors get 6 Coins per point (Tailwind applies), 10 Bolts (anti-farm rules) and an Expedition toward their island's tier. No PvP, building, buckets or containers there; a fall lands back on the pad. Journalled, so a crash never leaves one behind; staff `/expedition start [theme]` and `stop`
- [x] Trade Routes (`/route`): one island offers another a recurring contract (`/route offer <island> <sell|buy> <amount> <price> <daily|weekly>`, holding the item); the other's leader or an Admin accepts from chat or `/routes`. The seller sets a contract chest on their island (`/route chest <id>`; hoppers can fill it). At each delivery the goods leave the chest and, in one transaction, the buyer island's leader pays, the seller island's leader is paid less 3% tax, and the goods land in the buyer's depot (`/route depot`); each cycle can happen once. Missing goods, a missing chest or an unpaid bill is a miss (goods that left the chest go to the seller's depot); 3 in a row end the route. Islands take part in 2 routes, +1 per tier; offers expire after 72 h; disbanding ends an island's routes
- [x] Seasons (`/season`): 90 days; the leaderboard ranks worth gained this season (islands keep everything; new islands start from 0). At the end, every member of the top islands is recorded for a cosmetic (`title:season-<n>-champion`, `-podium`, `badge:season-<n>-top10`) in `skyrealms_season_awards` for the cosmetics engine, and paid Bolts (500/250/100) once; the next season starts at once. Staff: `/season end confirm`
- [x] Auction house listings: +1 per 100 island levels, up to +10 (rapid-market's listing bonus, never a rank perk)
- [x] Trading Post NPCs (Citizens, in the `skyrealms` plugin set): six in a ring round the compass rose, each with a floating label: Island Keeper (`/is`), Merchant (`/shop`), Realm Warden (`/realm`), Expedition Captain (`/expedition`), Route Master (`/route`), Lobby (to the hub). Built from the config every start, never saved by Citizens; positions in the skyrealms profile, `/skyrealms here` to place one
- [x] The SkyRealms sidebar was 16 lines, so TAB cut the `rapidskies.us` footer: "Players here" is gone (the tab list shows it)
- [x] Fixed on the way: the main class file was tracked as `RapidSkyrealms.java` (wrong case), which a case-sensitive CI checkout can't compile

Next:

- [x] Create the `superiorskyblock` schema and add `SS2_DB_*` to `secrets.env`, run the LuckPerms SkyRealms block, then `rsdeploy.py apply` and `plugins --restart` for skyrealms-1
- [x] Check on staging (mirroring skyrealms), then 1.20.1 and 26.3: the creation menu shows Meadow, Cherry Grove and Atoll; each pastes whole (tree, chest contents, sand), the player lands on the grass facing east; `/is` commands work for a non-op and `/is biome` doesn't; the generator gives Meadow odds; `/is upgrade` shows the six Coin upgrades and buying one charges Coins
- [x] Check `/realm`: goals tick (mine generator cobble, harvest wheat, sell, fish), placed blocks don't count, the ready message, ascending charges once and changes the generator and biome list; `/realm set` for staff; the Desert and Frost shop categories open
- [x] Check `/shop` and `/sell`: buy a stack, sell one and all, a named item refused, the rate line after selling past 20,000 (short test values on staging)
- [x] Check Expeditions with `/expedition start <theme>` for each theme and 1–3 players: the network warning on the hub, `/expedition go`, the boss bar, the heart's glow and label (seen through walls; the strike count updates; gone when it breaks), walking from the pad to the heart and onto the ship's deck, poop deck and hold, resource loot straight to the inventory, nothing else breaks or places, the heart split between two players, the summary and Coins when it leaves, everyone sent home, the blocks gone; kill the server mid-Expedition and see it removed on the next start
- [x] Check Trade Routes between two islands: offer, accept from the chat button, set a hopper-fed chest, a delivery with short test intervals (staging `routes.intervals`), the depot, a miss for an empty chest and for a buyer without Coins, the route ending after three misses, cancel and disband
- [x] Check `/season` and `/season end confirm` on staging (awards recorded, Bolts paid once, a new season begins)
- [x] Check the SkyRealms sidebar lines on 1.20.1 (lengths, the tier colors, the `rapidskies.us` footer shows)
- [x] Check the Trading Post NPCs after `rsdeploy.py apply` and `plugins --restart`: all six stand on the plaza facing the rose with their labels, turn to nearby players, and each click opens its menu (the Island Keeper: creation menu without an island, home with one; the Lobby: the hub queue)
- [x] Create one island of each layout and look underneath: the rock cone, roots and lichen; break the sand next to the beach on Atoll and nothing falls
- [x] NPCs at the Trading Post for the shop, Expeditions, Realm Tiers and Trade Routes (Citizens, like Horizons' spawn), once positions are picked in-game
- [ ] Tune in beta: shop prices and the soft cap against the Coins supply, tier goals and costs, upgrade prices, Expedition interval, size and pay, route limits and tax
- [ ] Later: Realm Tiers 4–5 (Nether Rift, Sky Citadel) with SuperiorSkyblock2's Nether and End dimensions; more Expedition themes (§12: weekly theme rotation); hub holograms for the season leaderboard

### Phase 5 progress

**Monetization & retention**: code complete, with 48 new tests. 32 are unit tests (the bundled catalog, quests and progression files load, and every id, item, particle, mob, slot and reward in them is real; the §8 compliance rules; the Featured Shop rotation never repeats week to week; the level curve; quest periods, banking and picks; login streaks); 16 run against MariaDB, including parallel clicks buying a cosmetic once, a rank pass delivered once, a level-up paying once, and two servers finishing the same quest paying once:

- [x] Cosmetics engine (`rapid-core`): six slots, one worn per slot: titles and badges (on the profile card shown when a name is hovered in chat, and `%rapid_title%` / `%rapid_badge%`), chat tags (a symbol after the rank prefix), name colors (named chat colors, never the staff reds), walking trails (particles at the feet, only for players who can see the wearer, never in vanish, staff or build mode; `cosmetics.trails` per server) and arrow trails (arrows, tridents, snowballs, eggs). Owned cosmetics in `cosmetics_owned`, rank sets worn while the rank lasts, the worn one remembered while unusable. `CosmeticService.grant` works for players offline or on another server (Redis `cosmetics`). `/cosmetics`: a tab per slot, owned first, locked ones with how to get them
- [x] Launch catalog (`cosmetics.yml`, in the jar so every server agrees): 102 cosmetics: shop items (150–1,200 Bolts), rank sets for Rapid+, Rapid++ and Rapid+++, level, achievement, Season Pass, Chapter (`title:pathfinder`), SkyRealms season (`title:season-<n>-champion`, `-podium`, `badge:season-<n>-top10`, numbered for any season) and event cosmetics (`title:founder`, `badge:beta-tester`), and 16 Featured-only ones. Every particle used exists on 1.20, and the tests check every particle and item name is real
- [x] Bolt shop (`/boltshop`, hub hotbar emerald): tabs for the Featured Shop, each cosmetic type, rank passes and the Season Pass; every purchase goes through a confirm screen and is one transaction (balance row locked, ownership checked, guarded debit, purchase row, delivery), so double clicks or two servers never charge twice. Everything sold is tagged `COSMETIC` or `CONVENIENCE` (§8)
- [x] Featured Shop: 4 Featured-only items a week, changing Monday 00:00 UTC, worked out from the week alone (nothing stored), each item coming round before any repeats and none two weeks running
- [x] Rank passes for Bolts (1,000 / 2,000 / 4,000 for 30 days, §6.1): charged on the backend, granted by the proxy through `/grant` (reason "Bolts pass", stacking with subscriptions, shown in `/grants`); delivered within seconds over Redis `rank-pass`, or by the proxy's check every minute. Refused while the player holds a higher rank
- [x] Rank stipend (§6.1): 100 / 250 / 600 Bolts once every 30 days while ranked, topped up on an upgrade, checked on join and every half hour
- [x] Network levels (§7, `/level`): 250 XP for level 2, +50 a level, at most 1,500, up to level 100; 25 Bolts a level (once ever, by transaction id, none for shared-IP accounts) and cosmetics at 5, 10, 20, 25, 30, 40, 50, 75 and 100, all in the same transaction as the XP. XP from quests, achievements, active play (10 per 15 active minutes, the first 3 hours a day) and events (Expeditions, Skyborne, the dragon, Chapters, parkour; amounts in `progression.yml`). Level-up title and sound; `%rapid_level%`, `%rapid_level_progress%`, and the level on the profile card and hub sidebar
- [x] Quests (§7, `/quests`, hub hotbar book): 3 dailies (one any-mode or hub, one Horizons, one SkyRealms), banked for 3 days so missed days can be caught up, and 2 weeklies (one per mode); 10 Bolts and 60 XP a daily, 75 Bolts and 350 XP a weekly. Objectives: natural blocks broken (blocks placed this session never count), ripe crops harvested, mobs killed (never spawner mobs), fish caught, active minutes, and from the gamemode plugins parkour runs, Expeditions, Coins earned at the SkyRealms shop, Skyborne wins and auction house purchases. Nothing counts while AFK. Assigned per player and period (the same on every server), counted in memory and written as increments every 30 s, when a quest looks done and on leaving; the database decides completion, so a quest finished during a server switch pays exactly once
- [x] Achievements (`/achievements`): 20 to start, on lifetime totals of the same objectives plus quests finished (10–250 Bolts, XP and 12 cosmetic rewards); awarded retroactively, so a new achievement reaches players already past it on their next join
- [x] Daily login streak (§6.2): 5, 10, 15, 20, 20, 20, then 25 Bolts a day; counted on whichever server the player joins first each UTC day, paid once by the day's transaction id
- [x] Season Pass (§7, `/pass`): Season 1 "First Light", 30 tiers of 550 season XP (the network XP earned while it runs), 6 free and 12 premium cosmetic rewards; premium for 900 Bolts, granting the premium rewards already reached at once. Seasons are dated in `progression.yml`; between seasons `/pass` shows the next one
- [x] Tebex (§6.3): `tebex-velocity` in the proxy plugin set; console deliveries with a transaction id are recorded in `store_transactions`; `rapid chargeback <player> <txn> [refund]` revokes the transaction's grants, takes back its Bolts (reporting any already spent), marks it and alerts staff chat. Package commands in infra/README.md → Store
- [x] Chapter and season cosmetics are owned for real now: Horizons grants the Chapter cosmetic on completion and SkyRealms grants season awards in the award's own transaction; migrations horizons V3 and skyrealms V2 hand over the ones recorded before
- [x] Compliance review (§8): done; see the notes there. Three items need people, not code: the store page wording, the published policies, and a final read of the current guidelines
- [x] Staff: `/progressadmin cosmetic give|take|list`, `xp`, `featured` (`rapid.admin.progress`, logged to `staff_actions`)
- [x] Hub: hotbar items for the Bolt shop, cosmetics and quests; the hub sidebar shows the level and open quests

Next:

- [ ] Deploy: `./gradlew dist`, `rsdeploy.py apply` (TAB sidebar) and `plugins --restart` on every server; the LuckPerms line for `rapid.admin.progress`; set up Tebex (infra/README.md → Store) and run a test purchase of each package type plus a chargeback on the Tebex sandbox
- [ ] Check on 1.20.1 and 26.3: every tab of `/cosmetics` and `/boltshop` (names, lore, the rarity colors, gradients in titles, the symbols in tags), buying a shop item and a Featured item (confirm screen, Bolts gone, `[Wear it]`), wearing each slot (the tag and name color in chat, the profile card on hover and its click, a trail seen by another player and hidden by the hub visibility toggle and vanish, an arrow trail), a rank set appearing with a `/grant` and disappearing when it's revoked, a rank pass bought with Bolts showing up as a grant within seconds
- [ ] Check quests: `/quests` on the hub, Horizons and SkyRealms (the mode lines), finish one of each objective (a placed-then-broken block doesn't count; a spawner mob doesn't), switch servers mid-quest and see it complete once, the banked dailies after a day away (staging clock or a test account), an achievement with its cosmetic
- [ ] Check levels and the pass: `/progressadmin xp` across a reward level (title, sound, Bolts, the cosmetic), `/pass` paging and the tier markers, buying premium with tiers already reached, the hub sidebar's Level and Quests line on 1.20.1
- [ ] Check streak and stipend on staging: first join of the day pays day 1, the next day day 2; a ranked player gets the stipend once, an upgrade tops it up
- [ ] Before launch: set Season 1's dates to the launch and add Season 2 (§12: about every 8 weeks); check the queue-priority and stipend notes in §8
- [ ] Tune in beta: Bolt prices against the 50–70 a day earn target (§6.2), XP amounts and the level curve, pass length and tier size, quest targets per mode, stipend and streak amounts
- [ ] Later: hub pets (Rapid+++, §6.1), Horizons banner patterns and nametag emblems, SkyRealms island particles and hologram colors (the mode-specific rank cosmetics); leaderboards for levels and the season pass; Discord linking and mentor rewards (§7)

### Everyday commands (`rapid-essentials`)

A plugin on every Paper backend (`paper-base` set) for the commands players expect from EssentialsX, built on rapid-core so each thing has one source of truth. 23 tests (3 against MariaDB):

- [x] One teleport path for `/spawn`, `/back`, `/home`, `/warp` and `/tpa`: per-kind cooldowns, an optional warmup that moving or damage cancels (3 s on Horizons), chunk loaded first, safe landing for remembered spots, and guards from other plugins. rapid-horizons refuses all of them while combat-tagged, so `/spawn` and `/home` are no escape from a fight any more (before, only `/back` was blocked); frozen players never move
- [x] Merged in: `/spawn`, `/setspawn` and `/afk` from rapid-core (the spawn, `WorldService` and the AFK tracker stay there as API), `/back` from rapid-horizons (now on every server but the hub; Horizons keeps "back from the portal = the spawn" through an origin filter), and the unsafe-block rules from Horizons' `/rtp` (`SafeBlocks`, shared)
- [x] `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpacancel`, `/tptoggle` on the same server, with clickable buttons; vanished staff can't be asked
- [x] Homes (`essentials_homes`): the same limit for everyone (3; §6.1), per server group, cached from pre-login, the limit checked in one locked transaction; `homes.blocked-worlds` for Frontiers. Warps (`essentials_warps`) set by admins
- [x] Coins/Crowns: `/balance`, `/pay` (one ledger transfer, both rows linked by a `pay:` transaction id), `/baltop` (town banks left out), `/eco give|take` (ledger reason `admin:<name>`, logged to `staff_actions`)
- [x] `/ping`, `/list` (rank prefixes; vanished staff only for those who can see them), `/seen` (staff), `/hat` (Horizons Rapid+ perk), `/rules`, `/store` (`/buy`, §6.3), `/website`, `/broadcast` over Redis
- [x] Per-server `disabled-commands` in the profiles: the hub keeps `/spawn` and the info pages; SkyRealms leaves homes to SuperiorSkyblock2
- [x] Staff tools (§9; never sold, §6.1): `/gamemode` (+ `/gm`, `/gms`, `/gmc`, `/gma`, `/gmsp`, a node per mode), `/fly`, `/god`, `/heal`, `/feed`, `/extinguish`, `/speed`, `/give`, `/i`, `/more`, `/repair [hand|all]`, `/clearinventory`, `/craft`, `/anvil`, `/grindstone`, `/loom`, `/smithingtable`, `/stonecutter`, `/cartographytable`, `/enderchest`, `/disposal`. Tiers: moderators get survival/adventure/spectator, flight, speed and healing on themselves; Sr. Mods the same on others, `/god` and the menus; admins creative and everything that makes items; builders the building ones on `build` only. Uses are logged to `staff_actions`; refused for anyone in staff or build mode; god mode can't hurt players; flight, god and speed given to someone without the node end at their next join; `/give` never drops what doesn't fit. Hub flight stays rapid-hub's `/fly`
- [x] Left out on purpose: kits (§6.1). `/msg`, `/r`, `/hub` stay on the proxy

Next:

- [x] Check on 1.20.1 and 26.3: the `/tpa` buttons and hovers, `/homes` and `/warps` click lists, the warmup cancelling on a step and a hit, `/spawn` refused while combat-tagged on Horizons, `/back` after the portal, `/pay` between two players and `/baltop`, `/broadcast` reaching both gamemodes, `/list` with a vanished staff member; the staff tools: `/gmc` and `/fly` surviving a relog and a respawn, `/fly <player>` ending when they relog, a moderator refused `/gmc`, `/give` with components (`diamond_sword[enchantments={sharpness:5}]`) and into a full inventory, `/anvil` and `/smithingtable` working with no block, `/god` blocking a hit on a player, and every tool refused in `/staff` and `/build`
- [x] Frontier worlds in `homes.blocked-worlds`: `rapidskies:frontier_*` (a trailing `*` now matches a key prefix)
- [x] Set the first warps on Horizons (`/setwarp`)
- [ ] Later: `/tpa` across servers (through the proxy, once Horizons has a second instance), `/ignore` with `/msg` on the proxy, and `/nick` for Rapid+++ (§6.1, staff-audited)

**Recommended launch strategy:** launch both modes together. With only two modes, launching one at a time would feel thin. Run Horizons as the first closed beta, though, since it relies more on premade plugins and will stress-test the core platform early. Don't wipe beta progress on SkyRealms or Horizons at launch.

### Test matrix (every release)

Clients: **1.20.1, 1.20.4, 1.21.11, 26.1.2, 26.3.** For each client, check:
- Login via `rapidskies.us` (SRV) and `mc.rapidskies.us`
- Chat colors, including every plus color and gradient
- Sidebar and tab, every GUI, holograms and NPCs, cosmetics visibility
- SkyRealms: create an island and complete one Expedition
- Horizons: claim land via `/claim map`, found a town, use a chest shop, travel to the Frontier, and fight in a Skyborne event
- Both modes: list, buy and collect on the auction house, and confirm the true item name shows for newer items on 1.20

**Automated tests (run in CI on every build):**
- MockBukkit unit and integration tests for the full claim protection matrix (§4.1).
- Auction house concurrency tests: parallel buy attempts on one listing, a server crash in the middle of listing or buying, and buying with a full inventory.

---

## 12. Post-launch live-ops cadence

- **Weekly:** Featured Shop rotation, quest rotation, Expedition themes, staff report review, economy health check.
- **Monthly:** new Frontier with a new or remixed modifier, a new Horizons Chapter, a Skyborne fortress variant.
- **Each season (~8–12 weeks):** new Season Pass, cosmetic drop, SkyRealms leaderboard reset with new Realm Tier(s), Trade and Town Project additions.
- **Each Minecraft release:** wait for stable Paper + Via + Velocity + SuperiorSkyblock2 builds, test on staging against the full matrix, then upgrade.

---

## 13. Open decisions

1. Gamemode 3 (decide about 6–8 weeks post-launch; see §5.4).
2. Paid secondary anti-cheat: Polar or Vulcan (decide after alpha data).
3. Claim precision: chunk-based is planned. Revisit sub-chunk claims inside town plots if beta players ask for finer control. Bid auctions and buy orders (`rapid-market` v2) are scheduled post-launch.
4. Final prices, Bolt earn rates and Tailwind numbers (tune with beta telemetry).
5. Website host: separate from the game box (SRV setup, recommended) or on the same box.
