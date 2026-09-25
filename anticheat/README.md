# CombatGuard (Fabric)

> Running a Paper server? Use the plugin in [`../plugin`](../plugin/README.md). This Fabric mod is then the
> optional **client mod** players can install: the plugin talks to it over plugin channels for injection and
> cheat mod detection.

Anticheat for **Minecraft 1.21.11 (Fabric)**. It has two parts in one jar:

- **Server side** (install on the server): movement, combat, world, inventory and packet checks. Works against
  every client, vanilla or not.
- **Client side** (optional, install on players' clients): answers integrity challenges from the server and
  reports cheat mods, injected code (Doomsday-style DLL / JNI / agent injection), JVM agents, suspicious native
  libraries and modified reach attributes. Turn on `client.requireClient` to make it mandatory.

Requires Fabric Loader 0.16+, Fabric API and Java 21.

## Build

```
cd anticheat
./gradlew build          # jar in build/libs/combatguard-1.0.0.jar, also runs the unit tests
```

GitHub Actions builds it on every push that touches `anticheat/` (workflow "Build CombatGuard anticheat", artifact
`combatguard-jar`).

## How detection works

Everything is based on what vanilla 1.21.11 actually does, read from the decompiled game code:

- **Client ticks.** Since 1.21.2 the client sends a `ClientTickEnd` packet after every tick. CombatGuard counts
  them, so clicks, block breaking and timing are measured in *client* ticks, which network lag cannot distort.
- **Packet order.** Inside one client tick vanilla always sends input (attacks, placing, digging, swings) before
  its movement packet, and at most one movement packet. Cheats that attack after sending a fake rotation break
  that order.
- **Transactions.** Every knockback, explosion and totem pop sent to a player is followed by a ping. The pong
  proves the client has processed the packet, so the velocity check knows exactly which movement must contain
  the knockback. A ping every second also measures real round-trip time for lag compensation.
- **Physics.** Speed and flight use vanilla's own formulas (friction, `0.216 / slipperiness³` acceleration,
  `(vy - gravity) * 0.98`), not distance limits.
- **Lag compensation.** Every player's hitbox is recorded each tick for two seconds; reach and aim checks test
  against every position the target had during the attacker's latency window (plus interpolation midpoints).

## Checks

| Check | Category | Types (flag names) | What it catches |
|---|---|---|---|
| Reach | combat | distance | Hits beyond the attack range (uses the item's attack range component, so spears work), lag compensated. Cancels hits far past the limit. |
| Hitbox | combat | aim, wall | The rotation sent in the attack's tick does not point at the target; hits through solid blocks. |
| KillAura | combat | multi, noswing, autoblock, container | Two targets in one tick, attacks without an arm swing, attacks while an item is in use (vanilla discards those clicks), attacks with a container open. |
| Aim | combat | pattern, snap | The exact same yaw step many ticks in a row; a big turn for a hit that is undone right after (silent aim). |
| AutoClicker | combat | cps, consistency | Sustained CPS above the limit; click intervals (in client ticks) too regular for a hand. Mining and block use are excluded. |
| Criticals | combat | hop, packet | Critical hit after a fake mini-jump or extra position packets. Mitigation clears the fall distance. |
| Velocity | combat | knockback, transaction | Knockback missing from the move right after the client confirmed receiving it (horizontal as a vector, vertical exactly); ignoring the ping sent after knockback. |
| Speed | movement | friction, strafe, noslow, keepsprint | Faster than vanilla friction allows; changing direction in the air more than air acceleration allows; full speed while eating/blocking; no slowdown after a sprint hit. |
| Flight | movement | gravity | Airborne vertical movement that does not follow gravity (fly, glide, hover, spider, air jump). |
| Jump | movement | high, step, low | Jumps higher than jump velocity, steps higher than step height, and tiny hops used by criticals. |
| GroundSpoof | movement | ground | Claims to be on the ground with nothing below (NoFall, Jesus, AntiHunger). Mitigation treats the packet as airborne so fall damage still happens. |
| FastClimb | movement | speed | Climbing ladders/vines faster than 0.1176 blocks per tick. |
| Phase | movement | vertical, horizontal | Moves whose path passes through a solid block (VClip, which vanilla accepts vertically). |
| Sprint | movement | direction, condition | Sprinting without holding forward (OmniSprint); sprinting while starving or blind. |
| Timer | movement | balance | More client ticks than real time allows (network-thread timestamps with a jitter balance). |
| Elytra | movement | speed, hover, energy | Faster than a dive allows, hovering, or gaining speed and height together without a rocket. |
| Vehicle | movement | fly | Boats, minecarts and horses staying in the air without falling. |
| Scaffold | world | face, hitvec, aim, rate | Clicking a block face that cannot be seen, hit positions off the face, placing where the player is not looking, inhuman placement rate. |
| Nuker | world | face, multi | Breaking hidden faces; starting to break two blocks in one tick. |
| FastBreak | world | speed | Finishing a block in fewer client ticks than the tool and effects need (vanilla accepts up to 30% early). |
| XRay | world | ratio | Tunnelling straight to ores that were not exposed to any cave air. Statistical, alert only. |
| BadPackets | packet | pitch, moves, post, slot, blink, transaction, ticks | Invalid pitch, several movement packets per tick, input after movement, weapon swaps within a tick, holding tick packets while answering pings (blink/fake lag), unanswered pings, missing tick packets. |
| Inventory | inventory | move, stealer | Clicking slots while movement keys are held (InvMove/AutoArmor); chest stealer timing. |
| AutoTotem | inventory | reaction | A new totem in the offhand within a tick of the client seeing the old one pop. |
| Client | client | mod, injection, reach, agent, attach, native, timeout, nonce, malformed | Blacklisted mods, attack/use/input calls from non-vanilla code or from memory, reach attributes above the server's, JVM agents, runtime attach, DLLs from temp/download folders. Needs the client mod. |

### About injected clients (Doomsday and similar)

No anticheat can promise to catch every injected client. What CombatGuard does:

1. **Behaviour (server side, works without the client mod):** whatever the cheat does in game still has to
   produce packets. Reach, aim, velocity, autoclicker, timer and the packet order checks flag the modules those
   clients sell.
2. **Integrity (client mod):** the client mod checks *who* calls attack, use and key-press code. Vanilla only ever
   calls them from a few known places; code injected with JNI or an agent, reflection calls and native hooks show
   up as a different caller, a class loaded from memory, or no Java caller at all. It also lists DLLs loaded from
   temp/download folders and JVM agents.

The client mod runs on the cheater's computer, so a determined cheat developer can patch it out. Treat it as an
extra layer; the server checks are the part that cannot be removed.

## Audit

This section covers the categories in the requested spec. **Covered** means a check that actually detects it,
**Partial** means some variants are caught, **Not covered** means nothing detects it.

### Coverage

| Area | Status | By | Notes |
|---|---|---|---|
| KillAura / MultiAura | Covered | KillAura, Hitbox, BadPackets/post, Aim/snap | Silent-rotation auras that attack in vanilla order with a matching rotation are only caught by Reach/Aim/Clicker. |
| Reach | Covered | Reach | Lag compensated; item attack ranges; mobs use a velocity-based margin instead of history. |
| Hitboxes | Covered | Hitbox/aim | |
| Attack through walls | Covered | Hitbox/wall | Solid collision blocks only (glass, leaves count; grass does not). |
| AimAssist / AimBot | Partial | Aim, Hitbox | Only machine patterns (constant steps, snap-back). Smooth, humanised aim assist is not detected. |
| Rotation (impossible pitch) | Covered | BadPackets/pitch | |
| Criticals | Covered | Criticals, Jump/low, BadPackets/moves | |
| NoSwing | Covered | KillAura/noswing | |
| AutoClicker | Partial | AutoClicker | Detects rate and regularity. Randomised clickers within human ranges pass. |
| AutoBlock / BlockHit | Covered | KillAura/autoblock | |
| Weapon switching | Covered | BadPackets/slot, BadPackets/post | |
| Velocity / AntiKB | Covered | Velocity | Explosion knockback is tracked but not judged (it adds to existing velocity). |
| Speed / BunnyHop / LongJump | Covered | Speed | |
| Strafe | Covered | Speed/strafe | In the air only; on the ground friction allows sharp turns. |
| Fly / Glide / Hover / AirWalk | Covered | Flight, GroundSpoof | |
| Spider / WallClimb | Covered | Flight | |
| FastLadder | Covered | FastClimb | |
| Step / HighJump | Covered | Jump | |
| NoFall / Ground spoof | Covered | GroundSpoof | |
| Jesus | Partial | GroundSpoof | Only modes that claim to be on the ground. Swimming-physics variants are not modelled. |
| NoSlow | Covered | Speed/noslow | |
| KeepSprint / OmniSprint | Covered | Speed/keepsprint, Sprint | |
| Timer | Covered | Timer | |
| Blink / FakeLag | Partial | BadPackets/blink | Catches clients holding tick packets while answering pings. Delaying everything uniformly looks like real lag and is not flagged. |
| Phase / VClip | Covered | Phase (+ vanilla collision checks) | |
| Freecam | Partial | Scaffold, Nuker, Hitbox, Reach, Client/mod | Actions from the detached camera position fail face/aim/reach checks; the freecam mod itself is blacklisted client side. |
| ElytraFly | Partial | Elytra | Speed cap, hover and energy gain. Tuned conservatively. |
| BoatFly / VehicleFly | Partial | Vehicle | Floating only. Vehicle speed is left to vanilla. |
| Scaffold / Tower | Covered | Scaffold, Flight, Jump | |
| FastPlace | Covered | Scaffold/rate | |
| Nuker / GhostHand | Covered | Nuker | |
| FastBreak | Covered | FastBreak | |
| XRay | Partial | XRay | Statistical; alert only. |
| InventoryMove / AutoArmor | Covered | Inventory/move | |
| ChestStealer | Covered | Inventory/stealer | |
| AutoTotem | Covered | AutoTotem | |
| Packet spoofing / order | Covered | BadPackets | |
| Packet flooding | Not covered | | Left to vanilla/network limits. |
| Automation (Baritone, AutoFish, AutoFarm) | Not covered | Client/mod for Baritone | No behavioural automation detection. |
| Player state spoofing | Partial | Sprint, GroundSpoof, BadPackets, Client/reach | Pose and swimming spoofs are not checked. |
| Injected clients | Partial | Client + all behaviour checks | See above. |

### Duplicates

Every check id is registered once in `CheckRegistry` (a duplicate id stops the server at startup). Overlapping
ideas were merged instead of added twice:

- NoSlow, KeepSprint and Strafe are types of Speed (same friction model), not separate checks.
- Step, HighJump and low jumps are types of Jump.
- AutoBlock and attacks in containers are types of KillAura.
- Attacks through walls are a type of Hitbox and reuse the lag-compensated boxes built for Reach.
- Knockback and the ping timeout share the transaction system used by AutoTotem and Blink.

### False-positive review

These need the most care when tuning (all values are in `config/combatguard.json`):

- **Aim/pattern, AutoClicker/consistency**: statistical. Keep them at alert only.
- **XRay**: lucky strip miners can trip it. Alert only by default.
- **Elytra/energy**: not tested against every flight style; the threshold is deliberately loose.
- **Inventory/stealer**: Mouse Tweaks style drag-shift-clicking can look like a stealer.
- **Client/injection**: legit mods that call attack/use code (Litematica easy place, controller mods, Tweakeroo)
  appear by mod id. Add them to `client.trustedCallerMods`.
- **GroundSpoof**: blocks broken under a player take a round trip to reach them; the buffer grows with ping.
- **ViaVersion servers**: older clients do not send tick packets. Set `BadPackets.options.allowLegacyClients` to 1;
  movement checks then skip those players.
- **Bedrock (Geyser)**: exempt by name prefix `.` by default.

Punishments (kicks and commands) are **off** by default (`punishmentsEnabled: false`). Watch alerts for a while,
then enable them.

### Performance

- Per player: a 40-entry hitbox history, 40-entry attribute history, 30 evidence entries, a few bounded click and
  delay queues (at most about 60 entries), and a 512-entry dug-block set for XRay. Roughly a few kilobytes.
- Per movement packet: two small block scans around the player (about 3x4x3 blocks each) and one entity lookup
  in a small box. A raycast only for moves longer than 0.5 blocks (Phase).
- Per attack: distance math over at most ~80 boxes; wall raycasts stop at the first clear line (usually one).
- Per server tick: one pass over online players (history, VL decay, transaction timeouts) and one ping per
  player per second.
- Network thread: timer and blink timestamps only; flags are handed to the server thread.
- Log file writes happen on a separate thread.

## Configuration

`config/combatguard.json` is created on first start. Top level:

| Key | Default | Meaning |
|---|---|---|
| `logLevel` | `INFO` | `OFF`, `ERROR`, `WARN`, `INFO` (one line per alert), `DEBUG` (every flag), `TRACE` |
| `logToFile` | `true` | Every flag to `logs/combatguard.log` |
| `alertsEnabled` | `true` | Staff alerts in chat |
| `alertFormat` | see file | Placeholders: `%player% %uuid% %check% %type% %vl% %ping% %tps% %version% %world% %x% %y% %z% %details%` |
| `alertCooldownMs` | `750` | Per player, check and type |
| `minTps` | `18.0` | Checks that rely on server timing (Reach, Hitbox, GroundSpoof, Vehicle) pause below this |
| `evidenceSize` | `30` | Flags kept per player for `/cg evidence` |
| `exemptOps` | `false` | Exempt operators |
| `exemptNamePrefixes` | `["."]` | Exempt names with these prefixes (Floodgate) |
| `punishmentsEnabled` | `false` | Master switch for kicks and punish commands |
| `kickMessage` | see file | `%check%` placeholder |
| `client.*` | | `requireClient`, challenge interval/timeout, `blacklistedMods`, `trustedCallerMods`, `trustedNativeLibraries` |

Per check (`checks.<Id>`): `enabled`, `alertVl`, `maxVl`, `kickVl`, `punishVl`, `punishCommands` (for example
`"tempban %player% 7d Unfair advantage"`), `decayPerSecond`, `mitigate` (cancel hits / set back), and `options`
(buffers and tolerances for that check).

## Commands

All under `/combatguard` (alias `/cg`), permission level 2 (game master / op).

| Command | What it does |
|---|---|
| `/cg alerts` | Toggle alerts for yourself |
| `/cg verbose` | Also see flags below the alert threshold |
| `/cg info <player>` | Ping (keep-alive and transaction RTT), TPS, client, world, position, VL per check, recent flags |
| `/cg evidence <player>` | The last flags with details, ping, TPS and position |
| `/cg client <player>` | Client integrity report (mods, agents, call origins, natives) |
| `/cg violations` | Online players sorted by total VL |
| `/cg checks` | Every check with category, state and thresholds |
| `/cg toggle <check>` | Enable or disable a check (saved) |
| `/cg exempt <player> <seconds>` | Exempt a player from all checks for a while |
| `/cg reset <player>` | Clear a player's violations |
| `/cg debug` | Switch console logging between INFO and DEBUG |
| `/cg reload` | Reload the config |
