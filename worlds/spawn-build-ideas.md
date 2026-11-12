# Spawn build ideas

Three spawn concepts each for the lobby (hub), SkyRealms and Horizons SMP, with sizes, layout, materials and build order. They're based on PLAN.md and the mode configs, so each build has a place for everything the plugins already put in a spawn.

---

## Rules for all nine

**The plugins place the interactive parts. The build only gives each one a place:**

| Thing | Placed by | What the build needs |
|---|---|---|
| NPCs | Citizens, from config (`/rapidhub here`, `/skyrealms here`, `/horizons here` print a position) | A 1-block pedestal, at least 3 blocks of clear air above it (NPC plus label), facing the spawn, with a dark or sky backdrop at label height so the text stays readable |
| Hub leaderboards | rapid-hub text displays | A low plinth or floor emblem with about 4 blocks of clear air above, visible from where players walk |
| Horizons portal | rapid-horizons `portal.region` (a box exactly 1 block thick), `portal.build`, `portal.hologram` | Either a clear opening for the built-in mossy arch, or your own gateway with `build: false` |
| Hub parkour | Generated in void lanes | Only an entrance spot for `parkour.courses.main.entrance` |
| Spawn point | rapid-core profile (SkyRealms also needs SuperiorSkyblock2's `spawn.location`) | A marked medallion or slab |

- **Version rule:** anything players walk on or jump across (floors, bridges, stairs, edges) uses blocks that exist in 1.20. Blocks newer than 1.20 (tuff bricks, copper bulbs and grates, pale oak, resin, leaf litter, wildflowers, copper lanterns, etc.) are for decoration only.
- **What players see on arrival:** from the spawn point, players should see the landmark, the NPCs and the way out. Keep the nearest NPC within 15–20 blocks and every required NPC within 30.
- **Sky island undersides:** shape the underside as an inverted cone 50–65% as deep as the top is wide. Go grass → dirt → stone/andesite → tuff → deepslate, with hanging roots and pointed dripstone at the tip. Make the rim ragged, not round. Players double-jump off the edges and see the underside, so detail it.
- **Leaves:** place leaves with `[persistent=true]` when using WorldEdit, or they'll decay.
- **Process:** build on the `build` server, export a schematic (like `revamped/horizons-smp-spawn.schem`), then check it on 1.20.1 and 26.3.

---

# LOBBY (hub)

**Needs:** a spawn medallion. Three game pedestals: SkyRealms, Horizons, and one held for gamemode 3. Leaderboard plinths for parkour (all-time and this week), plus room for island value/season, town size, Trade levels and Chapter completion. A parkour entrance. Open space for double jump. Use the brand palette: gold, yellow and black, with quartz and white for highlights. Both hubs share one map.

## Hub 1: Stormcatcher

**Pitch:** the network's currency is ⚡ Bolts, so the hub is a floating island that catches lightning. A black thundercloud hangs over a 64-block golden lightning-bolt spire. Each game sits at the end of a conductor bridge.

**Footprint:** a main island about 95×95 and three satellite islets 30–40 across, 45–55 blocks out. About 200 blocks across in total.

```
   SkyRealms islet        gm3 islet         Horizons islet
          \                   |                   /
           \ bridge           | bridge           / bridge
          (P1)              (P2)              (P3)    ← pedestals at bridge heads
             \________________|________________/
              pylon ring (leaderboards) + SPIRE
                              |
                     ★ spawn terrace (faces N)
                              |
                   parkour "test wire" gate (S rim)
```

- **Spawn:** a terrace 3 blocks above the plaza on the south side, facing north. The spire is centered, the cloud is above it and the three pedestals form a crescent in front.
- **Spire:**
  - Base: an octagonal plinth 15 wide and 4 high, in polished blackstone bricks with gold-block corner caps.
  - The bolt has three zig-zag segments that narrow from 5×5 to 3×3:
    - Segment 1 rises 24 and shifts 6 east, one step every 4 blocks.
    - Segment 2 rises 18 and shifts 8 west.
    - Segment 3 rises 22 and shifts 4 east, narrowing to 1×1.
  - Materials: a gold-block core with two faces in yellow concrete and two in yellow terracotta for shading. Black concrete trims the outer corner of each kink. Put sea lanterns in the seams.
  - Tip: a lightning rod with 3 end rods.
- **Thundercloud:** a 40×30 blob, 10 thick, starting 12 blocks above the tip. Black wool core, then gray wool, with a light-gray skin and a cobweb fringe. Hang chains 3–6 blocks down as "rain". Optionally inlay bolt shapes in yellow stained glass on the underside.
- **Plaza:** smooth quartz with 8 yellow-concrete spokes radiating from the spire, and grass and flower beds between the spokes. Each leaderboard plinth is a "storm pylon": a 3×3 polished blackstone base 2 high with a gold cap and end rods on the corners. Place 6 at radius about 22; the hologram floats above. Six leaves room for the future boards.
- **Bridges:** 5 wide. Smooth quartz down the middle 3, polished blackstone edges, and one gold-block "wire" line down the center. Chain and end-rod railings, with lanterns every 6 blocks.
- **Satellites** (scenery players can walk to; the NPCs stay at the bridge heads):
  - SkyRealms: a small oak island with tiny islets floating around it.
  - Horizons: a cottage meadow with banners for the six Trades.
  - gm3: a crate under scaffolding with diagonal yellow and black hazard stripes and a "coming soon" sign.
- **Parkour:** a black frame arch with yellow and black hazard stripes on the south rim ("test wire"), with the entrance spot in the middle.
- **Underside:** stone into tuff into deepslate, with veins of raw gold blocks and gold ore ("charged rock") and dripstone at the tip.
- **Build order:** island shape and underside → plaza grid and spokes → spire → crescent stage → bridges → satellites → cloud → detail.
- **Watch out:** NPC labels in front of the spire's gold will be hard to read. Put a black-concrete arch behind each pedestal.

## Hub 2: Skyport Terminal

**Pitch:** an airship station in the clouds. The hub is a departures hall and each game is a ship docked at a gate. It's the easiest of the three for new players to understand.

**Footprint:** an island 140×70 running east–west. The terminal hall in the middle is 70×28, and three piers run 35 blocks north.

```
  ~~~~~~~~~~~ open sky: ships moored ~~~~~~~~~~~
   [SkyRealms ship]   [gm3 hull]   [Horizons ship]
      pier A            pier B          pier C
  ┌──────────────────────────────────────────────┐
  │ Gate A (P1)       Gate B (P2)      Gate C (P3)│
  │ ▓ board ▓   yellow floor lines to each gate  ▓│ ← end walls: boards
  │          ★ mezzanine spawn (faces N)          │
  └──────────────────────────────────────────────┘
     south terrace: gardens, maintenance hangar (parkour)
```

- **Spawn:** a mezzanine 5 blocks above the concourse, looking north through a glass curtain wall at all three ships.
- **Hall:**
  - Interior height 14 or more, so double jump doesn't hit the roof.
  - Arched trusses in black concrete and polished blackstone, with a roof of glass and white stained glass. A big gold clock face hangs centered on the north wall.
  - Floor: a quartz and polished blackstone checkerboard with yellow-concrete walking lines to each gate, like airport floor markings.
  - Gate desks: smooth quartz counters with gold trim, the NPC standing in the gate opening, and a black-concrete gate sign above.
- **Leaderboards as departure boards:** black-concrete panels 9×5 in gold frames, one over each gate plus one on each end wall. Five boards, with the text displays set just in front of them.
- **Ships:**
  - SkyRealms: a striped yellow and white wool balloon 40 long and 14 across, with a birch/spruce gondola. A net of fences under it holds a mini sky island with an oak.
  - Horizons: a dark-oak sky-galleon 32 long with white wool sails, green banners and crates of saplings.
  - gm3: a hull frame in scaffolding with a crane, labeled "Gate B: coming soon". Finish it in that mode's theme once the mode is chosen.
- **Parkour:** a "maintenance hangar" on the south terrace. The door is a wall of iron trapdoors with yellow and black stripes, and the entrance spot is inside.
- **Underside:** an exposed "engine". A 9-wide core of glowstone and shroomlight inside polished blackstone turbine fins, implying the port is held up by engines.
- **Watch out:** the big balloon envelopes cast deep shadows on the piers. Use hidden light blocks underneath.

## Hub 3: The Orrery

**Pitch:** a giant floating sundial and astronomical clock. Seasons, Chapters and weekly boards all run on a timer, and this hub is the clock that keeps them. Gold rings arc overhead and three "planet" islands sit around it, one per game.

**Footprint:** a disk 101 across. The planets are 25–35 across, at radius about 75 and 120° apart: gm3 north, SkyRealms south-west, Horizons south-east.

- **Layout:**
  - The three pedestals sit on an inner ring at radius 15 around the gnomon, each facing its planet's bridge.
  - The spawn is at radius 25 on the south side, facing north.
  - The 12 hour markers on the rim are the leaderboard plinths, which leaves plenty of room for future boards.
- **Dial:**
  - The rim is a 3-wide ring of polished blackstone. Each hour marker is a 3×3 gold pillar 2 high, with a hologram above.
  - Roman numerals are inlaid in the floor in black on quartz.
  - Hour lines run from the center in yellow concrete, with planters and benches between them so the white face isn't flat.
- **Gnomon:** a 3-thick triangular blade 30 tall. The body is black and the slanted edge is gold.
- **Rings:**
  - A vertical meridian ring with radius 38 and a horizontal equator ring at height 30. Both are 2 thick, gold block with a yellow-terracotta inner face.
  - A 7-wide "sun" sits at the crown, made of ochre froglight and glowstone.
  - Draw the arcs with a circle generator or WorldEdit `//generate`, not by eye.
- **Planets:**
  - SkyRealms: an island surrounded by a ring of tiny floating islets.
  - Horizons: a green world with a small village.
  - gm3: a dark cratered moon under scaffolding.
- **Bridges:** 4 wide, smooth quartz with a gold center line, running from the inner ring to each planet.
- **Parkour:** a "pendulum pit" at the foot of the gnomon, with the entrance on a gold plate.
- **Watch out:** check from the spawn that no ring segment blocks a hologram.

---

# SkyRealms

**Needs:** six NPCs facing a central feature: Island Keeper, Merchant, Realm Warden, Expedition Captain, Route Master and Lobby. Space for season leaderboards (planned). Void edges; the void rescue catches falls.

If you move the spawn, update SuperiorSkyblock2's `spawn.location` and the skyrealms profile. It's currently `skyrealms_spawn, 28.5, 150, -29.5`.

**Themes to draw from:**
- Realm Tiers: Meadow → Desert → Frost → Nether Rift → Sky Citadel.
- Expeditions: Crystal Caverns, Sunken Ship, Overgrown Ruins.
- Trade Routes.
- Starter islands: Meadow, Cherry Grove, Atoll.

## SR1: The Drift Bazaar

**Pitch:** six islands tied together under hot-air balloons, drifting through the clouds. Every NPC runs a market stall. It's built around the economy: the shop, Coins, routes and the auction house.

**Footprint:** a central hexagon island 45 across with six "petal" islands 22–26 across around it, joined by rope bridges. About 120 across.

```
            [Seed Nursery]   [Weigh House]
                  \  P1        P2  /
   [Return Gate] P6 —  ✶ compass rose ✶ — P3 [Tier Gallery]
                  /  P5        P4  \
          [Contract House]   [Drift Dock]
                       ★ spawn (S stair, faces N)
```

- **Center:** a compass rose 21 across. Each of the six NPCs stands at radius 10 in front of a stall whose back opens onto that NPC's petal island.
- **Spawn:** on a raised entry stair just outside the ring, so all six NPCs are visible.
- **Petals:**
  - **Seed Nursery** (Island Keeper): three pedestals with miniatures of the real Meadow, Cherry Grove and Atoll starters.
  - **Weigh House** (Merchant): crates, barrels, wool sacks and a big balance scale made of chains and trapdoors.
  - **Tier Gallery** (Realm Warden): five arches, each framing a sample of one tier. Nether Rift and Sky Citadel are behind iron bars until those tiers launch.
  - **Drift Dock** (Expedition Captain): a pier with a moored sky-skiff, a map table, a spyglass stand and lit buoys running out into the void.
  - **Contract House** (Route Master): chiseled bookshelves, lecterns and a contract chest fed by a hopper. A chain pulley lowers crates onto a dangling cargo platform.
  - **Return Gate** (Lobby): a gold and black portal frame in the RapidSkies colors.
- **Balloons:** one tethered above each petal, 11–15 across, in striped wool in the petal's role color, with chains down. The center gets a large wool tent canopy with banners.
- **Palette:** spruce, oak and dark oak timber, stripped logs, orange/brown terracotta roofs, white and yellow wool canvas, cobblestone foundations, chains and lanterns.
- **Later:** turn the north facade of the Weigh House into a season leaderboard wall.

## SR2: Realm Ascent

**Pitch:** a floating mountain that shows the whole Realm Tier path in bands. You spawn on the Meadow band and can see your next goal above you. All six NPCs stay on the ground level.

**Footprint:** a base island about 110 across. The mountain rises about 85 above it, each band narrower than the one below.

| Band | Height | Look |
|---|---|---|
| Meadow (plaza) | +0–14 | Stone-brick plaza with a compass rose, grass, oaks, flowers and mossy stone |
| Desert | +15–30 | Sandstone terraces, orange/yellow terracotta, cactus, acacia and a small obelisk |
| Frost | +31–46 | Snow, packed and blue ice, spruce, calcite and small ice spikes |
| Nether Rift (sealed) | +47–62 | A crack lined with crying obsidian, blackstone, basalt, magma and crimson/warped, sealed with iron bars and chains |
| Sky Citadel (sealed) | +63–85 | A quartz, calcite and gold citadel above a cloud collar, with end-stone brick accents |

- **Layout:** the six NPCs stand in a semicircle facing the mountain. The spawn is at the south edge, facing north. The Expedition Captain stands on a dock reaching out from the edge, and the Route Master by a caravan of barrels.
- **Spiral road:** an optional road winds up the mountain to the Frost band and stops at the sealed Nether gate.
- **Later:** when tiers 4–5 launch, break the seals as a live reveal event.
- **Watch out:** keep the mountain behind the NPCs so it never blocks them. Place lava only where nobody can reach it.

## SR3: The Hollow Root

**Pitch:** on SkyRealms everything starts as a small island and grows, and so does the spawn. A huge tree grows from a small island, with roots hanging 60+ blocks into the void. The three starter islands hang from its branches as seed pods.

**Footprint:**
- Island: 70 across.
- Trunk: 18–22 across at the base, narrowing to 10.
- Tree: about 75 tall, with a canopy 80–90 wide.

- **Layout:**
  - The trunk is on the north side. South of it is a plaza with a compass rose, and the six NPCs stand in a crescent around it.
  - The roots form an arched alcove behind each NPC.
  - The spawn is south, facing the trunk.
  - The trunk is hollow, with an amber shroomlight heart where the Lobby gate glows.
- **Seed pods:** three open-faced spheres 9 wide, hanging from vines and chains, with the Meadow, Cherry Grove and Atoll miniatures inside. The Island Keeper stands under them.
- **Expedition and routes:**
  - A branch pier holds a skiff tied up.
  - Amethyst clusters on the roots hint at Crystal Caverns.
  - Chain-and-barrel lifts between branches stand in for Trade Routes.
- **Trunk materials:**
  - 60% dark oak wood (bark on all sides), 25% spruce wood, and 15% mangrove roots and muddy mangrove roots.
  - Build a spline core first, then add cylinders around it.
- **Roots, canopy and light:**
  - Roots: rooted dirt, hanging roots, moss carpet and glow lichen.
  - Light: cave vines with glow berries and spore blossoms.
  - Canopy: oak, azalea and flowering azalea leaves with cherry leaves as accents, all `persistent=true`.

---

# Horizons SMP

**Needs:** four NPC pedestals, all set in config:
- Heartland Guide: `rtp`.
- Land Surveyor: `command:claim help`.
- Trade Master: `trades`.
- Lobby: `server:hub`.

The config can add more NPCs, such as `command:ah`, `command:frontier` or `command:chapter`.

The Heartland portal needs a 1-block-thick region, a hologram spot, and either the built-in arch or yours with `build: false`. The Market Square Project's "market stall at spawn" is only recorded as unlocked; no code places a stall. So build empty stalls that staff can hand out to towns.

**Themes to draw from:** the six Trades, Frontier modifiers (Overgrowth, Shattered Skies, Deep Dark, Drought), Skyborne fortresses and Chapters.

There's already a revamp in progress in [revamped/](revamped/), so treat these as alternatives or ideas to borrow.

## H1: Waymeet (a market town at a crossroads)

**Pitch:** a frontier market town where four roads meet. The four NPCs stand around the central waystone, guild halls for the Trades line the roads, and the south road runs straight to the Heartland gate.

**Footprint:** about 130×130 with a central square 35×35.

```
            N: Guild Row (Miner · Enchanter · Builder)
                          │
 W: Market Lane ──── [ WAYSTONE ] ──── E: Guild Row (Farmer · Fisher · Hunter)
   (10 town stalls)    P1 ✦ P2
                       P3 ✦ P4      ★ spawn: N steps of the square, faces S
                          │
              S: Gate Road ──► Heartland Gate (portal), open sky beyond
```

- **Waystone:** a 5×5 base and a 13-tall standing stone in stone bricks, chiseled stone and mossy stone, topped with a lodestone. Four fence signposts carry hanging signs ("Heartland ↓", "Trades →", and so on). The NPC pedestals stand at its four corners.
- **Guild halls:** each facade shows its Trade:
  - Miner: a deepslate front with a minecart and rails coming out.
  - Farmer: a barn with hay and composters.
  - Hunter: a spruce lodge with skulls and banners.
  - Builder: a half-built workshop with scaffolding and a crane.
  - Enchanter: a purple-roofed tower with bookshelves and amethyst.
  - Fisher: a pond dock with barrels and cobweb nets.
- **Market Lane:** 10 identical stalls, each 3×3 with a striped wool awning and a blank banner post for the town that gets it. Number them so staff can track who has which.
- **Gate:**
  - Two stone towers, a spruce gatehouse and a 5×5 portal opening, with `build: false`.
  - The hologram floats above the opening.
  - Leave nothing behind the gate but sky and distant hills.
- **Palette:** spruce and oak, stripped logs, cobblestone and mossy cobblestone, stone bricks, mud bricks, dark-oak stair roofs, flower boxes made of trapdoors and flowers, hay and lanterns.

## H2: Horizon Harbor

**Pitch:** a cliffside harbor facing a sea that runs to the sunset, so the horizon is right there. A white and yellow lighthouse stands on the headland (a nod to the Lighthouse Town Project), and ships leave for the Heartland.

**Footprint:** about 150 along the coast and 90 deep, with a harbor basin 60 across.

- **Light and view:** lock the spawn world's time near 12000 (sunset) and face the spawn west so the sun sets straight ahead.
- **Layout:**
  - The town steps down in three terraces.
  - The top terrace has the spawn and the lighthouse path.
  - The middle terrace has the plaza with the four NPCs and the cartographer's hall.
  - The bottom is the quay: a fish market, the town stalls, and the portal as a departure arch at the end of the main pier.
- **Lighthouse:** a 7×7 base narrowing to 5×5, 45 tall, in bands of white concrete or calcite and yellow concrete. The lamp room is glass with sea lanterns and has a gold dome.
- **Cartographer's hall:** a wall of four wool mosaics, one per Frontier modifier. Light up the current one each month by swapping a banner or lantern.
- **Palette:**
  - Walls: white concrete, calcite and smooth quartz.
  - Roofs and trim: orange terracotta roofs with oak and spruce trim.
  - Accents: light-blue and cyan terracotta shutters, prismarine on the docks.
  - Cliffs: stone with diorite, granite and calcite layers.
- **Watch out:** fill water out to about 150 blocks and let fog hide the rest. Keep mob spawning off in the profile, or the bay fills with squid and drowned.

## H3: Skywatch Pass

**Pitch:** an expedition basecamp in a high mountain pass. A Skyborne fortress hangs in the sky as a set piece, and the Heartland valley opens beyond the pass.

**Footprint:** about 140×120. Two peaks about 60 tall flank a saddle where the camp sits.

- **Layout:**
  - The spawn is at the back of the camp, looking through the pass.
  - The four NPCs stand around a stone campfire ring.
  - The pass gate is the portal, framed by cliffs.
  - Beyond it, 60–120 blocks out, build a simpler valley as a backdrop for depth.
- **Features:**
  - **Skywatch tower:** a spyglass platform with signal braziers made of campfires.
  - **Frontier board:** four diorama plinths, one per modifier, with the current one lit.
  - **Chapter tent:** a lore wall for the current Chapter.
  - **Trade tents:** six workshop tents.
  - **Market:** a row of log-cabin stalls.
  - **Fortress:** 80 above the camp, a broken keep in deepslate bricks, blackstone and gold on an upside-down island hung with chains.
- **Palette:** stone, andesite, tuff and calcite cliffs; spruce and dark oak; white, gray, brown and yellow wool tents; snow caps, banners and lanterns.

---

## Quick picks

- **Hub:** Stormcatcher shows off the brand best. Skyport is the easiest for new players to understand.
- **SkyRealms:** Realm Ascent ties most directly to progression. The Drift Bazaar keeps the current six-NPC ring and plays best as a social space.
- **Horizons:** Waymeet uses every Horizons system (towns, Trades and stalls). Harbor gives the strongest first view.
