# CombatGuard for Paper

Anticheat plugin for **Paper 1.21.11** SMP servers.

## Install

1. Install [PacketEvents](https://modrinth.com/plugin/packetevents) 2.14.0 or newer (the spigot/paper jar) in
   `plugins/`. CombatGuard depends on it.
2. Put `combatguard-paper-1.0.0.jar` in `plugins/` and restart.
3. Give your staff `combatguard.alerts` (see alerts) and `combatguard.command` (use `/cg`). Ops have both.
4. Watch alerts for a few days. Kicks and punish commands are **off** until you set `punishments.enabled: true`
   in `plugins/CombatGuard/config.yml`.

Folia is not supported. Players on older client versions through ViaVersion don't send the 1.21.2+ tick packet;
set `checks.packet.badpackets.options.allowLegacyClients: 1.0` if you allow them, and movement checks will
skip those players.

Build it yourself with `./gradlew build` (jar in `build/libs/`). GitHub Actions builds it on every push that
touches `plugin/` (artifact `combatguard-paper-plugin`).

## How it works

- **PacketEvents** hands every packet to CombatGuard on the network thread, in the order the client sent it.
  CombatGuard turns them into small events and queues them per player.
- At the **start of every server tick** the queue is processed on the main thread. Checks run in exact packet
  order against the player's position as the client reported it. This keeps all Bukkit calls on the main thread
  and costs one pass per tick.
- Only three things run on the network thread: timer/blink timestamps (they need real arrival times), dropping
  attacks that are clearly impossible (hits far past reach, two targets in one tick), and rewriting spoofed
  "on ground" packets so fall damage still applies.
- **Transactions.** After every knockback, explosion and totem pop the plugin sends a ping. The pong proves the
  client applied the packet, so the velocity check knows exactly which move has to contain the knockback. A ping
  every second also measures real round-trip time for lag compensation.
- **Physics** use vanilla's formulas (friction, `0.216 / slipperiness³` acceleration, `(vy - gravity) * 0.98`),
  not distance limits. **Reach** is measured against every position the target had during the attacker's ping
  window (hitboxes are recorded every tick for two seconds).

## Checks

| Check | Types | Catches |
|---|---|---|
| Reach | distance | Hits beyond the attack range (spear attack range included), lag compensated. Hits more than 0.3 blocks past the limit are dropped. |
| Hitbox | aim, wall | The rotation sent with the hit does not point at the target; hits through solid blocks. |
| KillAura | multi, noswing, autoblock, container | Two targets in one tick, attacks without an arm swing, attacks while blocking/eating, attacks with a chest open. |
| Aim | pattern, snap | Exactly the same yaw step many ticks in a row; a big turn for a hit that is undone right after (silent aim). |
| AutoClicker | cps, consistency | Sustained CPS above 22; click timing too regular for a hand. High CPS alone is not flagged as consistency. |
| Criticals | hop, packet | Crits after fake mini-jumps or extra position packets. |
| Velocity | knockback, transaction | Knockback missing from the move right after the client confirmed it; ignoring the ping after knockback. |
| Speed | friction, strafe, noslow | Faster than friction allows (speed, bhop, long jump); turning mid-air faster than air acceleration; full speed while eating/blocking. |
| Flight | gravity | Fly, glide, hover, spider, air jump. |
| Jump | high, step, low | Jumps higher than jump velocity, steps higher than step height, fake mini hops. |
| GroundSpoof | ground | NoFall, Jesus, AntiHunger. |
| FastClimb | speed | Ladders/vines faster than 0.1176 blocks per tick. |
| Phase | vertical, horizontal | VClip and moving through blocks. |
| Sprint | direction, condition | OmniSprint; sprinting while starving or blind. |
| Timer | balance | More ticks than real time allows. |
| Elytra | speed, hover, energy | Elytra speed hacks, hovering, gaining speed and height without rockets. |
| Vehicle | fly | BoatFly / horse / minecart fly. |
| Scaffold | face, hitvec, aim, rate | Placing on hidden faces, fake hit positions, placing without looking, inhuman rate. |
| Nuker | face, multi | Breaking hidden faces, two blocks started in one tick. |
| FastBreak | speed | Breaking faster than tool and effects allow (vanilla accepts up to 30% early). |
| XRay | ratio | Tunnelling straight to ores not exposed to any cave air. Alert only. |
| BadPackets | pitch, moves, post, slot, blink, transaction, ticks | Impossible pitch, packet criticals, attacks/placing after a silent rotation, weapon swap within a tick, blink/fake lag, ignoring pings, missing tick packets. |
| Inventory | move, stealer | Using the inventory while walking (InvMove, AutoArmor), chest stealers. |
| AutoTotem | reaction | New totem in the offhand within a tick of the pop. |
| Client | mod, injection, reach, agent, native, ... | Needs the optional client mod (below). |

### Injected clients (Doomsday and similar)

A server plugin can't see inside a player's game, so it can't detect the injection itself. It catches what the
injected modules do: reach, aim, knockback, clicking, timer and packet order.

For more, players can install the optional **CombatGuard Fabric client mod** (the `anticheat/` folder of this
repository). The plugin talks to it over the `combatguard:challenge` / `combatguard:report` plugin channels.
It reports cheat mods, code injected into the game, JVM agents and DLLs loaded from temp or download folders.
It runs on the player's PC, so a determined cheat developer can bypass it. `client.require: true` kicks anyone
without it, which only makes sense if every player can install Fabric.

## Coverage

| Area | Status | Notes |
|---|---|---|
| KillAura / MultiAura / NoSwing / AutoBlock | Covered | |
| Reach / Hitboxes / hits through walls | Covered | |
| Criticals | Covered | |
| Velocity / AntiKB | Covered | Explosion knockback is tracked but not judged. |
| Speed / BHop / Strafe / LongJump / NoSlow | Covered | |
| Fly / Glide / Spider / AirWalk | Covered | |
| Step / HighJump / FastLadder | Covered | |
| NoFall / ground spoof | Covered | |
| Timer | Covered | |
| Phase / VClip | Covered | |
| Scaffold / Tower / FastPlace | Covered | |
| Nuker / FastBreak | Covered | |
| InventoryMove / ChestStealer / AutoTotem | Covered | |
| Packet order / silent rotations / weapon swap | Covered | |
| AimAssist | Partial | Only machine patterns. Humanised aim assist is not detected. |
| AutoClicker | Partial | Randomised clickers inside human ranges pass. |
| Blink / FakeLag | Partial | Delaying all packets evenly looks like real lag. |
| Jesus | Partial | Only modes that claim to be on the ground. |
| Freecam | Partial | Interactions from the detached camera fail face/aim/reach checks. |
| ElytraFly | Partial | Tuned conservatively. |
| BoatFly / VehicleFly | Partial | Floating only; vehicle speed is left to vanilla. |
| XRay | Partial | Statistical, alert only. |
| KeepSprint | Not covered | The plugin can't tell reliably whether the client applied the sprint-hit slowdown. |
| Packet flooding | Not covered | Paper has its own packet limiter. |
| Automation bots (Baritone, AutoFish) | Not covered | The client mod blacklists Baritone. |
| Injected clients | Partial | Behaviour only; the client mod adds more. |

## False positives to watch

- **Aim/pattern, AutoClicker/consistency, XRay**: statistical. Keep them at alert only.
- **Elytra/energy**: loose threshold, not tested against every flight style.
- **Inventory/stealer**: Mouse Tweaks style drag-shift-clicking can look like a stealer.
- **Custom plugins** that launch players (jump pads, grappling hooks) send velocity packets, which the anticheat
  tracks. Plugins that teleport players are handled through the teleport packets. If a plugin moves players in a
  way that still flags, exempt them briefly with `/cg exempt <player> <seconds>` or give `combatguard.bypass`.
- **Bedrock (Geyser)**: exempt by the `.` name prefix by default.

## Configuration

`plugins/CombatGuard/config.yml` is generated with comments on first start; `/cg reload` applies changes.

- `log-level`: `OFF`, `ERROR`, `WARN`, `INFO` (one line per alert), `DEBUG` (every flag), `TRACE`.
- `log-to-file`: every flag to `plugins/CombatGuard/violations.log`.
- `alerts.format`: placeholders `%player% %uuid% %check% %type% %vl% %ping% %tps% %version% %world% %x% %y% %z% %details%`.
- `min-tps`: checks that depend on server timing (Reach, Hitbox, GroundSpoof, Vehicle) pause below this TPS.
- `punishments.enabled` (default false), `punishments.kick-message`.
- `exempt.ops`, `exempt.name-prefixes`.
- `client.*`: the optional client mod (require, blacklist, trusted mods).
- `checks.<category>.<check>`: `enabled`, `alert-vl`, `max-vl`, `kick-vl`, `punish-vl`, `punish-commands`
  (e.g. `tempban %player% 7d Unfair advantage`), `decay-per-second`, `mitigate` (drop hits / set back), `options`
  (buffers and tolerances).

## Commands and permissions

`/combatguard` or `/cg` (`combatguard.command`):

| Command | What it does |
|---|---|
| `/cg alerts` | Toggle alerts for yourself |
| `/cg verbose` | Also see flags below the alert threshold |
| `/cg info <player>` | Ping (keep-alive and measured RTT), TPS, client, world, position, VL per check, recent flags |
| `/cg evidence <player>` | Last 30 flags with details, ping, TPS and position |
| `/cg client <player>` | Client mod report |
| `/cg violations` | Online players by total VL |
| `/cg checks` | Every check with state and thresholds |
| `/cg toggle <check>` | Enable/disable a check (saved) |
| `/cg exempt <player> <seconds>` | Exempt a player for a while |
| `/cg reset <player>` | Clear a player's violations |
| `/cg debug` | Switch console logging between INFO and DEBUG |
| `/cg reload` | Reload the config |

Permissions: `combatguard.command`, `combatguard.alerts` (both op by default), `combatguard.bypass` (nobody by
default; never flagged).

## Performance

- All checks run once per tick on the main thread over queued events; no per-player scheduled tasks.
- Per player: 40 hitboxes, 40 attribute samples, 30 evidence entries, small bounded click queues, a 512-entry
  dug-block set for XRay, and an event queue capped at 4000.
- Per movement packet: two small block scans around the player and one small entity lookup. Raycasts only for
  moves over 0.5 blocks (Phase) and for hits (wall check, usually one ray).
- One ping per player per second. Log file writes happen off the main thread.
