# WaveOptimizer

A Windows 10/11 gaming optimizer with a dark, animated UI. It reads your hardware, tells you what's actually limiting your FPS, and applies tweaks you can undo.

## Run it
1. Download the `WaveOptimizer` folder.
2. Double-click **`WaveOptimizer.bat`** and accept the admin prompt.

You don't need to install anything. It uses the PowerShell 5.1 and WPF that come with Windows.

## Tabs
| Tab | What it does |
|---|---|
| **Dashboard** | Shows your CPU, GPU (real VRAM), RAM (speed, sticks, DDR type), drive type, display Hz, OS. It also checks for the things that cost the most FPS: RAM running in single channel, XMP/EXPO turned off, the monitor stuck at 60 Hz, games on the iGPU, running on battery, an old GPU driver, an HDD, a full drive. |
| **Tweaks** | 20 switches covering Gaming, Power & CPU, Visuals, Background & Privacy, and Network. Presets: Safe / Gaming / Extreme. Hit **APPLY CHANGES**. You can create a restore point first. |
| **Cleanup** | Clears temp files, shader caches (DX/NVIDIA/AMD/Intel), the Windows Update cache, the Recycle Bin and DNS. Also has tools for startup apps, SFC/DISM repair, network reset and restore points. |
| **GPU & Games** | Pins any game .exe to the high-performance GPU. |
| **Minecraft** | Finds every `javaw.exe` (official launcher, Prism, CurseForge, TLauncher) and pins it to the dedicated GPU. Also lists the Sodium mod stack and gives JVM args sized to your RAM. |
| **Log** | Shows everything that was changed. |

## Undo
Before WaveOptimizer changes a registry value or service, it saves the original to `%ProgramData%\WaveOptimizer\backup.json`. To undo a tweak, turn its switch off and press APPLY, which puts back the exact original value. **Revert All** restores everything at once.

## What to expect
No Windows tweak will give you 4× FPS. These tweaks mostly cut stutter, input lag and background load. Expect a few percent on a clean PC and more on one with a lot of background junk. The large gains come from the problems the Dashboard flags (iGPU vs dGPU, XMP, dual-channel RAM, refresh rate) and, for Minecraft, from Sodium.
