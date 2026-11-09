# RapidSkies infra (Phase 0)

Network config as code for the Velocity proxy and Paper 26.3 backends on Pterodactyl.
DNS, the panel, Wings, the servers themselves, MariaDB and Redis already exist; this folder
holds what goes *on* them. The panel and the game node are separate machines (see below).

| Path | What it is |
|---|---|
| `network.yml` | Inventory: servers, which profiles / plugin sets / backup schedules each one gets |
| `profiles/` | Config patches per role (`velocity`, `paper-base`, `hub`, `survival`, `skyrealms`, `smp`, `build`, `staging`) |
| `rsdeploy.py` | Applies profiles, uploads plugin jars and sets backup schedules via the Pterodactyl client API |
| `secrets.env.example` | Template for `secrets.env` (git-ignored): forwarding secret, DB and Redis credentials, panel API key |
| `luckperms/bootstrap.txt` | One-time LuckPerms groups, tracks and permissions |
| `../worlds/` | Pre-built hub and spawn worlds, shipped by `rsdeploy.py worlds` (see [Worlds](#worlds)) |
| `host/` | Nightly MariaDB dump + offsite copy (systemd timer, rclone) |

## Where things run

The panel and the game node are separate machines.

| Machine | Runs | What from this folder goes there |
|---|---|---|
| **Your PC / CI** | `rsdeploy.py` | Everything except `host/`. Nothing is installed on either server. |
| **Panel server** | Pterodactyl Panel and its own database | `host/backup-db.sh` for the panel's database, if that's the only MariaDB there (see Backups) |
| **Game node** | Wings and every game container (Velocity, hubs, skyrealms, smp, build, staging) | `host/backup-db.sh` for the game databases and Wings backups |

How `rsdeploy` reaches them:

- API calls (reading and writing configs, schedules, power) go to the **panel** (`PTERO_URL`), which
  forwards file operations to Wings.
- Plugin uploads go **straight to the node's Wings** through a signed URL from the panel, so your
  PC must be able to reach the node's Wings address (its FQDN and port, usually 8080 or 443).
  Cloudflare Access headers are only sent to the panel, never to Wings.

Containers reach services on the **node** at `172.18.0.1` (the Pterodactyl Docker gateway). Anything
on the **panel server** is a different machine: use its private IP, not `172.18.0.1`. That matters for
`DB_HOST` and `REDIS_HOST` in `secrets.env`:

| MariaDB / Redis location | `DB_HOST` / `REDIS_HOST` | Also do |
|---|---|---|
| On the node (recommended: lowest latency, no cross-machine dependency for gameplay) | `172.18.0.1` | Bind them to `172.18.0.1` (or `0.0.0.0` with the firewall blocking public access) so containers can reach them |
| On the panel server | The panel server's private IP | Bind to that private IP and firewall the port so only the node's IP can connect. Never expose 3306/6379 publicly. |

Pterodactyl's database host setting (admin → Databases) must use the same address the
containers will use, since that's what it shows in each server's Databases tab.

## How profiles work

Configs are stored as **patches**, not whole files. Each server writes its own defaults on
first start; `rsdeploy` reads the live file, sets only the keys a profile names, and writes
it back. That survives upstream format changes and keeps secrets out of git.

- `profiles/<role>/<path>.patch` is a YAML map of `dotted.key: value` applied to `<path>`
  (`.properties`, `.yml`, `.toml`). A map value replaces the whole section.
- Any other file in a profile is written verbatim (`forwarding.secret`, `server-icon.png`, `.pteroignore`).
- `${NAME}` comes from `secrets.env` or the server's `vars`; `${addr:hub-1}` is that server's
  internal address, read from its Pterodactyl allocation. The tool refuses to use a backend
  whose allocation is on a public or wildcard IP.

## First-time rollout

```bash
pip install -r infra/requirements.txt
cp infra/secrets.env.example infra/secrets.env        # fill in; generate the forwarding secret
python infra/rsdeploy.py servers                      # lists panel servers with their IDs
```

Panel server names don't match the keys in `network.yml`, so each server is mapped by its
**`ptero_id`**: the 8 characters in its panel URL (`panel.rapidskies.us/server/<id>`). Copy each
ID from the `servers` output into `network.yml`, then run `servers` again: every entry should
show an ID, and every backend an allocation on `172.18.0.1`.

1. **Startup commands and Java.** Set each server's Docker image to Java 25 and its startup
   command as in [JVM flags](#jvm-flags) below (admin area → server → Startup; the client API
   can't change these). Set backup limits under Build Configuration (see `network.yml`).
2. **Plugins.** `python infra/rsdeploy.py plugins` uploads each server's jars from `plugins/`.
3. **Generate defaults.** Start every server once, let it finish booting, and stop it.
   The configs only exist after this.
4. **Configs.** `python infra/rsdeploy.py diff` to review, then
   `python infra/rsdeploy.py apply --restart`.
5. **Worlds.** With the servers stopped: `python infra/rsdeploy.py worlds --replace` swaps the
   worlds generated in step 3 for the hub and spawn builds (see [Worlds](#worlds)). Start them again.
6. **Backups.** `python infra/rsdeploy.py schedules` (Pterodactyl's own backups, stored on the
   node). Then install `host/backup-db.sh` as described in [Backups](#backups).
7. **Permissions.** Paste `luckperms/bootstrap.txt` into the Velocity console, then give yourself
   `owner`. Add staff to the `build`/`staging` whitelists from those consoles.
8. **Verify** (from a 1.20.1 client and a 26.3 client): join `rapidskies.us` and `mc.rapidskies.us`,
   land on the hub courtyard facing the gate hall, `/server` is denied for a default player, a 1.19.4 client gets the branded kick,
   and `/lp info` on the proxy shows MariaDB storage and Redis messaging connected.

Re-run `diff` / `apply` whenever a profile changes; both are idempotent.

### Things to know

- **Changing `level-name`** on a server that already booted makes it generate a new world
  (`hub`, `heartland`, `skyrealms_spawn`, `build`). Do it before builders import anything.
- **pvp, allow-nether and spawn-monsters are game rules on 26.x**, not server.properties keys.
  rapid-core sets them per world from the profiles (`worlds.*.game-rules`, see [Worlds](#worlds)).
- **Staging** mirrors smp by default and shares the LuckPerms database (separate `staging`
  context). When game plugins with their own databases go on staging, point them at separate
  schemas so tests never touch production data.
- **DDoS front:** if TCPShield or Spectrum sits in front of Velocity and sends PROXY protocol,
  set `advanced.haproxy-protocol: true` in `profiles/velocity/velocity.toml.patch`.
- **FAWE** for the build server isn't in `plugins/` yet. Add the jar and a glob to the `build`
  plugin sets when you have it.
- **Paid jars** (LiteBans) are git-ignored so they're never pushed; keep them in `plugins/` locally.

## Worlds

The hub and the two spawns are pre-built maps in the repo's `worlds/` folder. `network.yml` →
`worlds` names them, and each server's `worlds:` says where they go:

| Server | World | Goes to | Spawn (`spawn.location` in the rapid-core profile) |
|---|---|---|---|
| hub-1, hub-2 | Free Lobby | `hub` (main world) | `-5.5 87.5 9.5`, facing north: the slab on the south terrace, over the courtyard to the stage and gate hall |
| skyrealms-1 | Trading Post | `skyrealms_spawn` (main world) | `28.5 150 -29.5`, facing west: the centre of the compass rose, toward the pavilion |
| smp-1, staging | CottageCore Lobby | `heartland/dimensions/rapidskies/spawn` (`rapidskies:spawn`) | `0.5 74.1 0.5`, facing north: the village plaza's centrepiece, toward the town hall |

```bash
python infra/rsdeploy.py worlds --dry-run               # what would happen
python infra/rsdeploy.py worlds hub-1 --replace         # server must be stopped
```

- **Only chunk storage is shipped:** `region/`, plus `entities/` (item frames, paintings, armor
  stands) and `poi/` where the map has them. On Paper 26.1+ every world lives inside the main
  save: the main world in `<level-name>/dimensions/minecraft/overworld/`, a world loaded by
  key in `<level-name>/dimensions/<namespace>/<key>/`. rsdeploy puts them there; the
  downloads' own layout (region/ at the top) would be ignored and an empty world generated
  instead.
- **No level.dat is shipped.** The server writes fresh world data on the next start:
  the generator comes from the profile (`level-type`/`generator-settings` in server.properties
  for the main world, so run `apply` first; `create:` in rapid-core's `worlds` for the rest),
  and rapid-core sets the spawn point, game rules, time, weather and border on every start.
  The downloads' level.dat files had wrong spawns, flat grass under the Trading Post, vanilla
  terrain around the CottageCore island and a 1.8 village preset under the hub.
- **Old chunks are upgraded by Paper on first load.** The hub's chunks are from 1.8 (numeric
  block IDs), the Trading Post's and the CottageCore Lobby's from 1.21.8. Walk the whole build once after the first start
  and look for missing or wrong blocks; to upgrade everything up front instead, start the
  server once with `--forceUpgrade` added to its startup command.
- **Existing worlds are never overwritten** unless you pass `--replace`, and then they're
  moved to `world-backups/` (excluded from Pterodactyl backups; delete them once you're happy).
  Never use `--replace` on a world players have built in.
- **Spawn behaviour** (rapid-core, `spawn.*` and `worlds.*` in each profile): the hub puts
  players at the spawn on every join; SkyRealms and Horizons only on a player's first join, and
  after deaths without a bed or respawn anchor. All three worlds are `protect`ed: only
  `rapid.world.build` (ops by default) can change blocks, containers, item frames or armor
  stands, and fire, explosions, leaf decay and fading are off. Falling below `void-y` (all
  three are sky islands) sends players back to the spawn. `/spawn` works everywhere;
  `/setspawn` (op) moves the spawn until the next restart and prints the line to put in the
  profile, which is where the spawn really lives.

## Hub (rapid-hub)

The hub servers get the `hub` plugin set on top of `paper-base`: Citizens and `rapid-hub`. Its
positions live in `profiles/hub/plugins/rapid-hub/config.yml.patch`, like the spawn:

| What | Where on the Free Lobby |
|---|---|
| Mode NPCs (SkyRealms, Horizons) | The stage's inner slab pedestals, `-7.5 86.5 -10.5` and `-3.5 86.5 -10.5`, facing the spawn. The centre pedestal is kept for gamemode 3. |
| Leaderboards (parkour, all time / this week) | Over the floor emblems either side of the courtyard, `-16 86 -1` and `5 86 -1` |
| Parkour course `main` | Generated per run in the `rapidskies:parkour` void world (rapid-core creates it from the hub profile), 150 blocks with checkpoints at 50 and 100. Started from the hotbar feather or `/parkour start`; an entrance plate in the courtyard is optional (`parkour.courses.main.entrance`) |

- **Nothing is saved into the world.** NPCs (an in-memory Citizens registry) and the floating
  labels (text displays) are built from the config on every start, so moving one is a config
  change, never a cleanup job. Their chunks are kept loaded with plugin tickets.
- **Placing things:** stand where it should go and run `/rapidhub here`: it prints the
  position as an NPC/leaderboard line and as a parkour block, each click-to-copy. Edit the
  live `plugins/rapid-hub/config.yml`, `/rapidhub reload` to try it, then copy the final
  values into the profile (or the next `apply` puts the old ones back).
- **Perks** come from permissions (`luckperms/bootstrap.txt`): `rapid.hub.trail` (Rapid+) and
  `rapid.hub.fly` (Rapid++), both with `server=hub`.

## Lang overrides

Each rapid plugin copies its bundled `lang/en_US.yml` into its data folder on first start, and
every key in that copy overrides the jar. Reworded messages therefore don't reach a server by
updating the jar alone: pin them in a `lang/en_US.yml.patch` in the right profile (as done for
the SkyRealms rename), or delete the server's copy so the next start writes a fresh one.

## Backups

Three things need backing up, and with the panel on its own server they live in different places:

| Data | Lives on | Covered by |
|---|---|---|
| Server files (worlds, configs) | Node (`/var/lib/pterodactyl/volumes`) | Pterodactyl scheduled backups → stored on the node in `/var/lib/pterodactyl/backups` |
| Game databases (LuckPerms, later rapid-*, SuperiorSkyblock2) | Wherever MariaDB runs | `backup-db.sh` on that machine |
| Panel database (users, servers, schedules, API keys) | Panel server | `backup-db.sh` on the panel server |

Install `host/backup-db.sh` on **every machine that has a MariaDB or Wings backups**, each with its
own `/etc/rapidskies/backup-db.env` (instructions at the top of the script):

- **Node:** `WINGS_BACKUP_DIR=/var/lib/pterodactyl/backups`. If MariaDB runs here, set `DB_*` to it;
  otherwise set `DUMP_DATABASES=false` and the node only ships Wings backups offsite.
- **Panel server:** `DB_*` pointing at the local MariaDB (this covers the panel database, and the
  game databases too if they're here). Leave `WINGS_BACKUP_DIR` unset: there's nothing to copy.
- Give each machine its own `RCLONE_REMOTE` path under the same bucket (e.g. `b2:rapidskies-backups/node`
  and `.../panel`) so their retention cleanup never touches each other's files.

If you use S3/B2 for Pterodactyl backups instead of local storage (`config.yml` → `backups`
on the node), Wings uploads them itself and the node only needs the database part.

Also keep a copy of the panel's `/var/www/pterodactyl/.env`: it holds `APP_KEY`, without which the
encrypted values in the panel database (node tokens, database host passwords) can't be decrypted
after a restore. Store it in your password manager, not in git.

## JVM flags

Java 25. Heap is sized as a percentage of the container limit, so the same command works for
every RAM size; the remaining 15% covers metaspace, Netty buffers and thread stacks.

**Paper backends** (Aikar's flags, large-heap variant since the game servers are ≥ 10 GB; fine for 3–4 GB too):

```
java -XX:InitialRAMPercentage=85.0 -XX:MaxRAMPercentage=85.0 -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=40 -XX:G1MaxNewSizePercent=50 -XX:G1HeapRegionSize=16M -XX:G1ReservePercent=15 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=20 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true -Dterminal.jline=false -Dterminal.ansi=true -jar {{SERVER_JARFILE}} --nogui
```

**Velocity:**

```
java -XX:InitialRAMPercentage=85.0 -XX:MaxRAMPercentage=85.0 -XX:+UseG1GC -XX:G1HeapRegionSize=4M -XX:+UnlockExperimentalVMOptions -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:MaxInlineLevel=15 -jar {{SERVER_JARFILE}}
```

Both flag sets were checked to start cleanly on JDK 25.0.5.

## Plugin jars

`plugins/` at the repo root holds the third-party jars; `network.yml` → `plugin_sets` decides
which server gets which. To upgrade a plugin, replace its jar in `plugins/` and run
`rsdeploy.py plugins`: the old version on each server is removed automatically. Upgrade on
`staging` first (plan §12).

Custom plugins (`rapid-*`) are built by Gradle (`./gradlew dist` → `build/dist/`) and by CI
on every push. Plugin sets name them with a `dist/` prefix (`dist/rapid-core.jar`), so run
`./gradlew dist` before `rsdeploy.py plugins`. Their file names never change, so rsdeploy
compares them by SHA-256 rather than size; builds are reproducible, so an unchanged build is
never re-uploaded. `rsdeploy.py plugins --restart` restarts the servers whose jars changed.

New schemas for phase 1: **LiteBans** keeps its data in its own `litebans` schema (create it like
the others; credentials are `LITEBANS_DB_*` in `secrets.env`). Its chat lines, ban/kick screens
and Discord embed colors are restyled to the brand in `velocity/plugins/litebans/messages.yml.patch`
(LiteBans reads `&` codes, not MiniMessage). **TAB** runs on Velocity with
**VelocityScoreboardAPI** (needed for scoreboard teams); **TAB-Bridge** on every backend passes
rapid-core's `%rapid_prefix%` and vanish state up to it, so the tab list and nametags match chat.
TAB also draws the sidebar, one layout per kind of server, chosen by the backend's name
(`%server%<-hub`, `<-skyrealms`, `<-smp`, else a plain one); the values come from rapid-core
and rapid-hub placeholders through TAB-Bridge (`tab/config.yml.patch` lists them).

**VaultUnlocked** (the `survival` set) carries the Vault API on the gamemode servers. rapid-core
registers the Coins (skyrealms) or Crowns (smp, staging) economy through it, picked by
`server.gamemode` in the `smp`/`skyrealms` profiles. It implements the classic Vault interface,
which SuperiorSkyblock2 uses; when QuickShop-Hikari joins the smp set (phase 3b), set its economy
to the classic Vault provider, not VaultUnlocked's newer one.
