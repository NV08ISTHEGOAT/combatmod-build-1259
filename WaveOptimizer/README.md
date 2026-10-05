# WaveOptimizer

A Windows 10/11 gaming optimizer with a dark, animated UI. It reads your hardware, tells you what's actually limiting your FPS, and applies tweaks you can undo.

## Run it
**The app:** go to this repo's **Releases**, open **WaveOptimizer (latest build)**, and download **`WaveOptimizer.exe`**. Double-click it and accept the admin prompt. You don't need to install anything.

- Windows SmartScreen may say *"Windows protected your PC"* because the exe isn't code-signed. Click **More info → Run anyway**.
- Some antivirus programs flag *any* PowerShell-compiled exe. If yours does, use the script version below, which is the same code in a plain-text file you can read.

**Script version:** download the `WaveOptimizer` folder and double-click **`WaveOptimizer.bat`**.

## How the exe is built
Every push that changes `WaveOptimizer/` runs the **Build WaveOptimizer** GitHub Action on a Windows VM:
1. **Self-test:** loads the real UI, scans the hardware and checks every tweak's status (read-only).
2. **Round-trip test:** applies and then reverts every non-network tweak on the throwaway VM, and fails the build if anything isn't restored exactly.
3. **Build:** `build.ps1` renders the logo into `WaveOptimizer.ico` and compiles the script with [ps2exe](https://github.com/MScholtes/PS2EXE).
4. **Exe test:** runs the self-test again inside the compiled exe.
5. **Publish:** uploads the exe to the `waveoptimizer-latest` release with its SHA-256.

To build it yourself on Windows, run `powershell -ExecutionPolicy Bypass -File build.ps1`. The exe ends up in `dist\`.

## Tabs
| Tab | What it does |
|---|---|
| **Dashboard** | Shows your CPU, GPU (real VRAM), RAM (speed, sticks, DDR type), drive type, display Hz, OS. It also checks for the things that cost the most FPS: RAM running in single channel, XMP/EXPO turned off, the monitor stuck at 60 Hz, games on the iGPU, running on battery, an old GPU driver, an HDD, a full drive. |
| **Tweaks** | **191 tweaks** in 14 categories, with search, category filters and **All on / All off** per category. Presets: Safe (69) / Gaming (134) / Extreme (169). Changes you haven't applied yet are outlined in cyan, and **APPLY** shows how many there are. You can create a restore point first. Tweaks that don't fit your PC are hidden: services or tasks that aren't installed, Windows-11-only features on Windows 10, NIC features your adapter lacks. |
| **Cleanup** | Clears temp files, shader caches (DX/NVIDIA/AMD/Intel), the Windows Update cache, the Recycle Bin and DNS. Also has tools for startup apps, SFC/DISM repair, network reset and restore points. |
| **GPU & Games** | Pins any game .exe to the high-performance GPU. |
| **Minecraft** | Finds every `javaw.exe` (official launcher, Prism, CurseForge, TLauncher) and pins it to the dedicated GPU. Also lists the Sodium mod stack and gives JVM args sized to your RAM. |
| **Log** | Shows everything that was changed. |

## Tweak categories
| Category | # | Examples |
|---|---|---|
| Gaming | 10 | Game Mode, Game DVR off, MMCSS game priority, windowed-game flip model + VRR, timer resolution |
| Mouse & Keyboard | 8 | Mouse acceleration off, Sticky/Filter/Toggle Keys popups off, Alt+Shift layout switch off |
| GPU & Display | 5 | HW GPU scheduling, MPO fix, TDR delay, NVIDIA telemetry |
| Power & CPU | 14 | Ultimate plan, core parking, boost mode, EPP, PCIe ASPM, USB/Wi-Fi power saving, Fast Startup |
| Memory & Storage | 13 | SysMain, indexer, memory compression, NTFS last-access, reserved storage |
| Network | 13 | Nagle, throttling index, P2P updates, Cloudflare DNS, EEE / interrupt moderation / LSO, SMBv1 |
| Windows Visuals | 12 | Visual effects, transparency, animations, dark mode |
| Explorer & Taskbar | 18 | File extensions, classic context menu, Widgets/Copilot/Recall off, "End task" on taskbar |
| Ads & Notifications | 15 | Start/lock-screen/Settings ads, Bing in Start, feedback nags |
| Privacy & Telemetry | 17 | Telemetry, advertising ID, activity history, error reporting |
| Scheduled Tasks | 17 | Compatibility Appraiser (CPU spikes), CEIP, Device Census, updater tasks |
| Services | 37 | Fax, Remote Registry, Xbox, Print Spooler, Bluetooth... (the ones that break things are off in every preset) |
| Apps & Browsers | 6 | Edge/Chrome background mode, Edge bloat, OneDrive autostart |
| Windows Update | 6 | No auto-restart while signed in, no driver replacement |

Risk badges: **SAFE** (green), **MODERATE** (amber, has a trade-off that's explained), **ADVANCED** (red, test it and revert if it's worse). Before applying, WaveOptimizer lists every Moderate/Advanced tweak you're about to turn on.

## Undo
Before WaveOptimizer changes a registry value, service, scheduled task, power setting, network adapter property or DNS server, it saves the original to `%ProgramData%\WaveOptimizer\backup.json`. To undo a tweak, turn its switch off and press APPLY, which puts back the exact original value. **Revert All** restores everything at once.

## What to expect
No Windows tweak will give you 4× FPS. These tweaks mostly cut stutter, input lag and background load. Expect a few percent on a clean PC and more on one with a lot of background junk. The large gains come from the problems the Dashboard flags (iGPU vs dGPU, XMP, dual-channel RAM, refresh rate) and, for Minecraft, from Sodium.
