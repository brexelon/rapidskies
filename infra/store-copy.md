# Tebex store copy (store.rapidskies.us)

Copy for the webstore, ready to paste. Each block is HTML: in the Tebex editor, switch the
description to its source or HTML view and paste the block in. Setup and package commands
are in [README.md → Store](README.md#store-tebex-storerapidskiesus).

Facts below match the code and config as of 2026-11-11 (PLAN.md §6). When a price, perk or
amount changes, change it here too. Sources: rank perks in `rapid-proxy` `config.yml`
(party/friends/queue), `rapid-core` `progression.yml` (stipend) and `cosmetics.yml` (rank
sets, rank passes, shop prices), `PlusColor.java` (plus colors), `infra/luckperms/bootstrap.txt`
(hub trail and flight, `/hat`).

**Before publishing:**
- The links to `rapidskies.us/terms`, `/refunds` and `/privacy` are placeholders: point them
  at the real pages (PLAN.md §8 needs them published).
- Perks from the plan that aren't built yet are left out on purpose: hub pets, `/nick`, chat
  emotes, colored `/msg`, early access to new drops, and the mode cosmetics other than `/hat`
  (island hologram colors and particles, banner patterns, nametag emblems, claim-border
  styles, town title effects). Add each one here when it ships, not before.

---

## 1. Store front page: About

Goes in the homepage / "About" module. Covers the server and the store.

```html
<h2>Welcome to RapidSkies</h2>
<p>RapidSkies is a Minecraft: Java Edition network with two long-term survival worlds that are never wiped. Join at <strong>rapidskies.us</strong> from any version between 1.20 and 26.3.</p>
<ul>
  <li><strong>SkyRealms</strong>: co-op SkyBlock. Grow your island, unlock new Realm Tiers, race other islands on Expeditions, and sign Trade Routes with your neighbors.</li>
  <li><strong>Horizons</strong>: survival SMP. Claim land, found a town, pick up a Trade, and head out to a new Frontier world every month without ever losing your builds.</li>
</ul>
<p>Between games, the hub has parkour with a fresh course on every run, leaderboards, daily and weekly quests, a network level, and a Season Pass.</p>

<h3>Cosmetic and convenience only</h3>
<p>Nothing in this store gives you a gameplay advantage. You can't buy items, gear, kits, Coins, Crowns, claim chunks, island upgrades, homes, multipliers or cooldown skips, with money or with Bolts. What you can buy changes how you look, plus a few extras like bigger parties and friend lists. There are no crates, keys or paid random rewards.</p>

<h3>What's in the store</h3>
<ul>
  <li><strong>Ranks</strong>: Rapid+, Rapid++ and Rapid+++, monthly. A prefix with your own plus color, a cosmetic set, hub perks, and Bolts every month.</li>
  <li><strong>Bolts ⚡</strong>: the network currency. Spend them in the in-game Bolt shop on cosmetics, the weekly Featured Shop, the Season Pass premium track, or 30-day rank passes.</li>
</ul>

<h3>Everything here can also be earned</h3>
<p>Bolts come from playing: daily logins, active playtime, quests, events, level-ups and achievements. Every rank can also be bought in-game with Bolts as a 30-day pass. Buying something supports the server and keeps it running, but you never need to.</p>

<h3>How it works</h3>
<ul>
  <li>At checkout, enter your Minecraft: Java Edition username exactly as it appears in game.</li>
  <li>Your purchase is delivered automatically, usually within a few minutes. You don't need to be online.</li>
  <li>In game: <strong>/boltshop</strong> to spend Bolts, <strong>/plus</strong> to choose your plus color, <strong>/cosmetics</strong> to wear what you own, <strong>/grants</strong> to see your rank and when it ends.</li>
  <li>Something missing after an hour? Contact us through <strong>rapidskies.us</strong> with your username and the transaction ID from your receipt.</li>
</ul>

<h3>Refunds and chargebacks</h3>
<p>Purchases are digital and delivered right away. See our <a href="https://rapidskies.us/refunds">refund policy</a> before you buy. Opening a chargeback removes everything that purchase delivered and flags the account for staff review.</p>

<p><small><a href="https://rapidskies.us/terms">Terms</a> · <a href="https://rapidskies.us/refunds">Refund policy</a> · <a href="https://rapidskies.us/privacy">Privacy policy</a><br>
Payments are processed securely by Tebex.<br>
NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.</small></p>
```

### Short version

For a sidebar, footer or text module where the full About is too long.

```html
<p><strong>RapidSkies</strong> is a Minecraft: Java Edition network (1.20 to 26.3) at <strong>rapidskies.us</strong>, home to SkyRealms co-op SkyBlock and Horizons survival SMP. Everything in this store is <strong>cosmetic or convenience only</strong>, and all of it can also be earned by playing.</p>
<p><small>Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.</small></p>
```

---

## 2. Ranks category

Category description (shown above the three rank packages).

```html
<p>Monthly ranks that change how you look on the network, never how you play. Each tier includes everything from the tiers below it.</p>
<table>
  <thead>
    <tr><th></th><th>Rapid+</th><th>Rapid++</th><th>Rapid+++</th></tr>
  </thead>
  <tbody>
    <tr><td>Price</td><td>$4.99/mo</td><td>$9.99/mo</td><td>$19.99/mo</td></tr>
    <tr><td>Plus colors</td><td>7</td><td>13</td><td>14 + 14 gradients</td></tr>
    <tr><td>Bolts every 30 days</td><td>100</td><td>250</td><td>600</td></tr>
    <tr><td>Cosmetic set</td><td>Rapid+</td><td>+ Rapid++</td><td>+ Rapid+++</td></tr>
    <tr><td>Hub</td><td>Double-jump trail</td><td>+ Flight</td><td>+ Flight</td></tr>
    <tr><td>Party size</td><td>6</td><td>8</td><td>12</td></tr>
    <tr><td>Friends list</td><td>150</td><td>250</td><td>500</td></tr>
    <tr><td>Queue priority</td><td>✔</td><td>✔</td><td>✔ Highest</td></tr>
  </tbody>
</table>
<p><small>Without a rank: parties of 4, 100 friends.</small></p>
<ul>
  <li>Each payment adds 30 days. Cancel anytime: your rank stays until the time you've paid for runs out.</li>
  <li>If you hold two ranks at once, both keep counting down and the higher one shows.</li>
  <li>Queue priority only moves you up the line when a server is full. Everyone still gets in.</li>
  <li>Prefer not to subscribe? Every rank is also sold in-game as a 30-day pass for Bolts in <strong>/boltshop</strong>.</li>
</ul>
<p><small>Cosmetic and convenience only. No rank gives items, Coins, Crowns, claims, homes, island size, multipliers or commands like /fly outside the hub.</small></p>
```

### Rapid+ ($4.99/month subscription)

```html
<p><strong>RAPID+</strong> next to your name in chat, the tab list and above your head, with the + in a color you choose.</p>
<h4>Your look</h4>
<ul>
  <li>7 plus colors with <strong>/plus</strong>: White, Yellow, Gold, Aqua, Green, Light Purple and Red</li>
  <li>The Rapid+ cosmetic set: the <strong>Swift</strong> title, the <strong>Spark ✦</strong> chat tag and the <strong>Sunbeam</strong> walking trail</li>
  <li>A particle trail on your hub double jump</li>
  <li>Horizons: <strong>/hat</strong> to wear the block you're holding</li>
</ul>
<h4>Extras</h4>
<ul>
  <li><strong>100 Bolts</strong> every 30 days while your rank is active</li>
  <li>Parties of up to 6 players (normally 4)</li>
  <li>Up to 150 friends (normally 100)</li>
  <li>Queue priority when a server is full</li>
</ul>
<p><small>Renews every 30 days; cancel anytime. Also available in-game as a 30-day pass for 1,000 Bolts. Cosmetic and convenience only.</small></p>
```

### Rapid++ ($9.99/month subscription)

```html
<p><strong>RAPID++</strong> with a wider choice of colors, a bigger cosmetic set and flight in the hub. Includes everything in Rapid+.</p>
<h4>Your look</h4>
<ul>
  <li>13 plus colors: the Rapid+ seven, plus Blue, Dark Blue, Dark Aqua, Dark Green, Dark Purple and Dark Red</li>
  <li>The Rapid++ cosmetic set, on top of Rapid+'s: the <strong>Storm</strong> title, the <strong>Twinkle ✧</strong> chat tag, the <strong>Dark Aqua</strong> name color, the <strong>Starlight</strong> walking trail and the <strong>Comet</strong> arrow trail</li>
  <li>Fly around the hub</li>
  <li>Hub double-jump trail and Horizons <strong>/hat</strong></li>
</ul>
<h4>Extras</h4>
<ul>
  <li><strong>250 Bolts</strong> every 30 days while your rank is active</li>
  <li>Parties of up to 8 players</li>
  <li>Up to 250 friends</li>
  <li>Queue priority when a server is full</li>
</ul>
<p><small>Renews every 30 days; cancel anytime. Also available in-game as a 30-day pass for 2,000 Bolts. Cosmetic and convenience only.</small></p>
```

### Rapid+++ ($19.99/month subscription)

```html
<p><strong>RAPID+++</strong> in bold, every plus color and gradient, the full cosmetic collection and the most Bolts. Includes everything in Rapid+ and Rapid++.</p>
<h4>Your look</h4>
<ul>
  <li>Every plus color: all 13 from Rapid++, plus Gray</li>
  <li>14 gradients for your +++: <strong>Sunset, Ocean, Aurora, Frost, Citrus, Twilight, Silver, Crimson, Inferno, Rose, Peppermint, Berry, Firework</strong> and <strong>Volcano</strong></li>
  <li>The Rapid+++ cosmetic set, on top of Rapid+ and Rapid++'s: the <strong>Stormcaller</strong> title, the <strong>Supernova ✪</strong> chat tag, the <strong>Dark Purple</strong> name color and the <strong>Rapid Storm</strong> walking trail</li>
  <li>Hub flight, hub double-jump trail and Horizons <strong>/hat</strong></li>
</ul>
<h4>Extras</h4>
<ul>
  <li><strong>600 Bolts</strong> every 30 days while your rank is active</li>
  <li>Parties of up to 12 players</li>
  <li>Up to 500 friends</li>
  <li>Highest queue priority when a server is full</li>
</ul>
<p><small>Renews every 30 days; cancel anytime. Also available in-game as a 30-day pass for 4,000 Bolts. Cosmetic and convenience only.</small></p>
```

---

## 3. Bolts category

Category description (shown above the four bundles).

```html
<p><strong>Bolts ⚡</strong> are the RapidSkies network currency. One balance works on every server, and you spend it in game with <strong>/boltshop</strong>.</p>
<h4>What Bolts buy</h4>
<ul>
  <li><strong>Cosmetics</strong>, yours to keep: titles, chat tags, name colors, badges, walking trails and arrow trails (150 to 1,200 Bolts)</li>
  <li><strong>The Featured Shop</strong>: 4 cosmetics you can't get anywhere else, changing every Monday</li>
  <li><strong>The Season Pass premium track</strong>: extra cosmetic rewards every season (900 Bolts)</li>
  <li><strong>30-day rank passes</strong>: Rapid+ (1,000), Rapid++ (2,000) or Rapid+++ (4,000)</li>
</ul>
<h4>What Bolts don't do</h4>
<p>Bolts can't buy items, gear or anything that helps you win, can't be traded to other players, and can't be turned into SkyRealms Coins or Horizons Crowns.</p>
<h4>Earn them for free</h4>
<p>You also get Bolts for daily logins (up to 25 a day on a streak), active playtime, quests, events, achievements and every network level-up.</p>
<p><small>Delivered automatically, usually within a few minutes, even if you're offline. Shop prices may change.</small></p>
```

### 500 Bolts ($4.99)

```html
<p><strong>500 Bolts ⚡</strong> added to your account.</p>
<p>Enough for a rare title or trail from the Bolt shop with change to spare, or most items in this week's Featured Shop.</p>
<p><small>Spend in game with /boltshop. Cosmetic and convenience only. Bolts can't be traded or turned into Coins or Crowns.</small></p>
```

### 1,100 Bolts ($9.99)

```html
<p><strong>1,100 Bolts ⚡</strong> added to your account. That's <strong>10% bonus</strong> Bolts compared with the 500 bundle.</p>
<p>Enough for the Season Pass premium track (900 Bolts), or a 30-day Rapid+ pass with Bolts left over.</p>
<p><small>Spend in game with /boltshop. Cosmetic and convenience only. Bolts can't be traded or turned into Coins or Crowns.</small></p>
```

### 2,400 Bolts ($19.99)

```html
<p><strong>2,400 Bolts ⚡</strong> added to your account. That's <strong>20% bonus</strong> Bolts compared with the 500 bundle.</p>
<p>Enough for a 30-day Rapid++ pass, or the Season Pass premium track plus a handful of cosmetics.</p>
<p><small>Spend in game with /boltshop. Cosmetic and convenience only. Bolts can't be traded or turned into Coins or Crowns.</small></p>
```

### 6,500 Bolts ($49.99)

```html
<p><strong>6,500 Bolts ⚡</strong> added to your account. That's <strong>30% bonus</strong> Bolts compared with the 500 bundle, the best value in the store.</p>
<p>Enough for a 30-day Rapid+++ pass and the Season Pass premium track, with Bolts left for the Featured Shop.</p>
<p><small>Spend in game with /boltshop. Cosmetic and convenience only. Bolts can't be traded or turned into Coins or Crowns.</small></p>
```
