# Voxy server + dashboards — AI handoff

Written 2026-08-20. Covers the infrastructure and tooling built this session. The **mod** has its
own handoff at `~/temp/Github-NOTSYNCED/voxy_worldgen_v2-unified/HANDOFF.md` — read that one for
anything about the code; this file is about the machines, the dashboards and the hardware control.

---

## Where things run

| | |
|---|---|
| **razer@192.168.1.29** | THE LIVE BOX. Ubuntu, 16 cores, 15 GB RAM, disk 85% full. Runs three things (below). |
| **xps@192.168.1.23** | DEAD. 2-amber/4-white Dell diagnostic = memory failure. Reported 8 GB when it should have 16; one SODIMM has failed. Its world is stranded on that disk but is **no longer needed** — Alex prefers the razer seed. |

### On razer

| Service | Where | Port | Screen | Control |
|---|---|---|---|---|
| **voxy** (this project) | `~/Documents/minecraft/voxy` | 25566 | `voxy` | `~/voxy-ctl {start\|stop\|status\|console\|log}` |
| **BMC3** (unrelated, pre-existing) | `~/Documents/minecraft/BMC3_Server_Pack_v45 2` | 25565 | `mc` | `~/bin/mc-ctl`, watchdog cron `*/2` |
| **mcweb** (dashboard) | `~/mcweb` | 8899 | `mcweb` | `python3 ~/mcweb/mcweb.py` |

**voxy serves `vanilla.alextoddslick.net`.** It took over port 25566 from an older vanilla server,
so existing TCPShield/DNS routing needed no change. That old server's 2.3 GB world is preserved at
`~/Documents/minecraft/vanilla.removed-20260820/` and as a 1.6 GB tarball in `backups/` — **not yet
deleted**, and worth reclaiming (disk is at 85%).

**Do not disturb BMC3.** It has its own watchdog; Alex's notes record a previous watchdog SIGTERMing
a healthy server out from under a connected player. Never point anything new at screen `mc`.

### The world — do not lose this

```
seed 8382891732453678346    KEEPER WORLD, Alex likes this seed
```
Recorded in `server.properties` and twice in `~/Documents/minecraft/voxy/README.txt`. The xps world
lost its seed to a powered-off box because `level-seed=` was left empty and randomised; do not repeat
that. Weekly backup runs Sunday 04:00 (`backup.sh`, zstd -19, 4 retained, no downtime via
save-off/flush/save-on with an EXIT trap that always restores save-on).

**There is no watchdog for voxy.** BMC3 has one; voxy does not. If its process dies it stays dead.

---

## Firewall — this has bitten three times

`ufw` is active and default-deny inbound. **Every new port needs an explicit rule**, and the symptom
is always a connection *timeout* (not "refused"), which reads like the service is broken:

```
sudo ufw allow from 192.168.1.0/24 to any port <PORT> proto tcp
```

Done so far: 22, 25565, 25566, 25567. **Port 8899 (the web dashboard) is still NOT open** — that is
why the dashboard is unreachable from the LAN even though it is running and healthy.

---

## Hardware control (Razer Blade 15 Advanced, RZ09-0330)

Installed this session: `razer-laptop-control` (userspace daemon over USB HID — **not** a kernel
module). Service: `systemctl --user status razercontrol`. Socket: `/tmp/razercontrol-socket`.

**`~/razer-mode {silent|quiet|balanced|gaming|creator|max|custom|fan <rpm|auto>|turbo <on|off>}`**

Four traps, all of which cost real debugging time:

1. **`/usr/local/bin/razer-cli` is a DIFFERENT tool** (OpenRazer, keyboard lighting) and shadows the
   laptop CLI in PATH. Always the absolute `/usr/bin/razer-cli`.
2. **Power mode 4 (Custom) silently blocks manual fan control.** The write returns `result: true`
   and the fan reverts to `Auto`. `max` therefore uses Gaming (1), never Custom.
3. **The Razer power profile does not stop CPU turbo.** Silent lowers the EC budget but the chip
   still boosts to 3.8 GHz, which is where the heat is. Turbo is a separate lever:
   `sudo -n /usr/local/sbin/mc-turbo {on|off|status}` → writes `intel_pstate/no_turbo` and caps
   `max_perf_pct` to 70. Reached through ONE narrowly-scoped sudoers rule
   (`/etc/sudoers.d/mc-turbo`); the helper accepts only `on|off|status`, no paths, no free-form args,
   and must stay root-owned or the grant becomes privilege escalation.
4. **Fan + power persist across reboot** (the daemon restores from `~/.local/share/razercontrol`);
   **turbo does not** — `intel_pstate` resets to enabled. Re-apply a preset after a restart.

Fan range is **3500–5300 RPM**, `0` = auto. Verified against `laptops.json` for pid `0253`.

**No fan sensor exists in hwmon** — no `fan*_input`, no `pwm*`. All fan data comes from the daemon.

**GPU telemetry is broken.** RTX 2070 Super Max-Q, driver 535.309.01 loaded, but `nvidia-smi` cannot
get a device handle and `runtime_status` reads `error` — a stuck power state, not idleness. Likely
needs a module reload or reboot. Deliberately NOT wired into any dashboard rather than showing a
fake or permanently-blank row.

---

## Dashboards

### CLI — `~/mc-top [interval]` (works today)

Run with `ssh -t` (needs a TTY). Shows per-core CPU + MHz, per-server CPU/RSS/PID, fan + power mode,
all thermal zones, memory/swap, disk, and voxy generation stats.

Two implementation notes that are easy to regress:
- **Flicker-free by construction**: the screen is cleared ONCE at startup; each refresh homes the
  cursor and erases only to end-of-line. Clearing the screen per tick is what caused the flicker Alex
  complained about.
- **Per-process CPU comes from `/proc/PID/stat` deltas**, not `ps %cpu` (which averages over the
  process's whole lifetime — meaningless for a server up 12 days). The helper sets a global rather
  than echoing, because `$(...)` would run it in a subshell and discard the previous-sample state.
  That bug shipped once and showed as `--%` forever.
- Voxy stats poll every 10s deliberately — each `voxygen status` writes 4 lines to the server log.

### Web — `http://192.168.1.29:8899` (running, firewalled off)

`~/mcweb/{mcweb.py,mccontrol.py,dashboard.html}`. Python 3 **stdlib only**, no root. Measured
overhead 28 ms/sample = 0.56% of one core. Routes: `/api/now`, `/api/history?range=`, `/api/control`,
`/healthz`.

**Security posture, stated plainly:** there is **no authentication** on `/api/control`. It restricts
to private source IPs and supports a `--token` flag that is not enabled. Anyone on the LAN can
restart a server. Acceptable on a home network; know it before exposing the box more widely.

### Web v2 — IN FLIGHT at handoff time

Design direction **"Timeline"** chosen from three concepts
([canvas](https://claude.ai/code/artifact/a8801ae0-bda1-4bb8-901b-70cb44c2ff69), sources in
`/tmp/mcdash-design/`). Its idea: one shared 60-minute x-axis across the page; "now" is a vertical
rule; current values are the right-hand edge of history rather than separate tiles.

A workflow is building it into `/tmp/mcweb2/` with Alex's corrections applied:
- **host/PC settings collapsed** behind one control (the draft's permanent host rail is wrong)
- **each server a lane that expands in place**, actions hidden until expanded
- fan slider + power buttons + turbo toggle + named presets
- multi-server console with quick commands, localStorage favourites, and autocomplete over the real
  MC 26.2 command list
- **the fan slider must disable itself in Custom mode, and the backend must refuse the write too** —
  one layer is not enough given trap #2

**If that workflow's output is unusable, the v1 dashboard at `~/mcweb` still works** — deploy over it
only once v2 is verified.

---

## Tuning — the thing to get right

Current: `generationRadius 128`, `spawnPregenRadius 128`, and `maxActiveTasks` has been raised as
high as **128**, which is too high.

The bottleneck is **not generation, it is transmission.** Voxy has been observed at **818% CPU** with
`active tasks 109/128` while the LOD sender falls behind and defers chunks (53,481 at peak). Raising
`maxActiveTasks` produces chunks faster than one sender thread can serialise, deflate and transmit,
so the surplus is deferred and regenerated — churn, not speed.

**Recommendation: `maxActiveTasks` ≈ 16.** On the xps box, 10 drained the send queue completely.
That also relieves the memory pressure — razer is running ~13 of 15 GB with ~2 GB of swap in use, and
swap under a JVM heap hurts tick times for BMC3 as well as voxy.

**C2ME would not help** and was deliberately excluded: it parallelises *generation*, which is already
saturating, and would hand the overwhelmed sender more work. It is also the specific condition behind
the historical `getChunk` main-thread deadlock (fixed this session, but still).

"Chunks dropped" in `/voxygen status` means **deferred and retried**, not lost — and the same chunk
can be counted many times, so the number overstates the problem. Before this session's fixes a
dropped chunk *was* lost permanently; that was one cause of the missing LOD band.

---

## Open items

1. **Open port 8899 in ufw** — the dashboard is running and unreachable without it.
2. **Lower `maxActiveTasks` to ~16.**
3. **Deploy web dashboard v2** when the build finishes; verify its charts plot correctly against a
   known value (the Timeline *mockup* had a real chart-scale bug — a 400% gridline drawn at ~353%).
4. **No watchdog for voxy.** Add one modelled on BMC3's, but it must only recreate a *dead* server —
   never restart a running one.
5. **Reclaim disk**: `rm -rf ~/Documents/minecraft/vanilla.removed-20260820` (2.4 GB, backed up).
6. **GPU telemetry** stuck; needs a module reload or reboot (reboot takes both servers down).
7. **xps RAM** — reseat/replace the failed SODIMM if that machine is wanted again.
8. **Two unported guards** in the mod: `PlayerTracker.reconcile` and `reapStuckTasks`. Without
   `reconcile`, a stale player entry means the worker never idles — which would silently park the
   spawn anchor. See the mod's own HANDOFF.
9. **Check for more unpushed local commits** — the near-LOD-ring fix existed only on the *local*
   `port/26.2` branch and was missed because the port read `origin/port/26.2`. Diff the two.

---

## The pattern worth remembering

Three separate features shipped **inert** this session because a class was ported without its call
site: `LodSendQueue` (no `startSendQueue`), `TabHud` (no `tick`), `LodMemory` (entirely orphaned — no
`record`, `tick` or `onDisconnect` caller). Every one presented as "the feature does nothing", never
as an error. **When porting, grep for callers of every new class.**
