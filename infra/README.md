# RapidSkies infra (Phase 0)

Network config as code for the Velocity proxy and Paper 26.3 backends on Pterodactyl.
DNS, the panel, Wings, the servers themselves, MariaDB and Redis already exist; this folder
holds what goes *on* them.

| Path | What it is |
|---|---|
| `network.yml` | Inventory: servers, which profiles / plugin sets / backup schedules each one gets |
| `profiles/` | Config patches per role (`velocity`, `paper-base`, `hub`, `survival`, `skyrealms`, `smp`, `build`, `staging`) |
| `rsdeploy.py` | Applies profiles, uploads plugin jars and sets backup schedules via the Pterodactyl client API |
| `secrets.env.example` | Template for `secrets.env` (git-ignored): forwarding secret, DB and Redis credentials, panel API key |
| `luckperms/bootstrap.txt` | One-time LuckPerms groups, tracks and permissions |
| `host/` | Nightly MariaDB dump + offsite copy (systemd timer, rclone) |

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
python infra/rsdeploy.py servers                      # every server found, backends on 172.18.0.1?
```

1. **Startup commands and Java.** Set each server's Docker image to Java 25 and its startup
   command as in [JVM flags](#jvm-flags) below (admin area → server → Startup; the client API
   can't change these). Set backup limits under Build Configuration (see `network.yml`).
2. **Plugins.** `python infra/rsdeploy.py plugins` uploads each server's jars from `plugins/`.
3. **Generate defaults.** Start every server once, let it finish booting, and stop it.
   The configs only exist after this.
4. **Configs.** `python infra/rsdeploy.py diff` to review, then
   `python infra/rsdeploy.py apply --restart`.
5. **Backups.** `python infra/rsdeploy.py schedules`, then install `host/backup-db.sh` on the
   host (instructions at the top of the script).
6. **Permissions.** Paste `luckperms/bootstrap.txt` into the Velocity console, then give yourself
   `owner`. Add staff to the `build`/`staging` whitelists from those consoles.
7. **Verify** (from a 1.8.9 client and a 26.3 client): join `rapidskies.us` and `mc.rapidskies.us`,
   land on a hub, `/server` is denied for a default player, a 1.7.10 client gets the branded kick,
   and `/lp info` on the proxy shows MariaDB storage and Redis messaging connected.

Re-run `diff` / `apply` whenever a profile changes; both are idempotent.

### Things to know

- **Changing `level-name`** on a server that already booted makes it generate a new world
  (`hub`, `heartland`, `skyrealms_spawn`, `build`). Do it before builders import anything.
- **pvp, allow-nether and spawn-monsters are game rules on 26.x**, not server.properties keys.
  Set hub game rules in-game once the hub build is imported (rapid-hub also enforces them in phase 2).
- **Staging** mirrors smp by default and shares the LuckPerms database (separate `staging`
  context). When game plugins with their own databases go on staging, point them at separate
  schemas so tests never touch production data.
- **DDoS front:** if TCPShield or Spectrum sits in front of Velocity and sends PROXY protocol,
  set `advanced.haproxy-protocol: true` in `profiles/velocity/velocity.toml.patch`.
- **FAWE** for the build server isn't in `plugins/` yet. Add the jar and a glob to the `build`
  plugin sets when you have it.
- **Paid jars** (LiteBans) are git-ignored so they're never pushed; keep them in `plugins/` locally.

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
on every push. They start shipping to servers in phase 1.
