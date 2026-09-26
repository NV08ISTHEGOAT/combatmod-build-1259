# WaveClips

Clipping, recording and editing for Windows, in the WaveClient style: aqua on navy, glowing borders, corner accents and flowing waves.

- **Replay buffer clipping**: press your clip key and the last 15 s – 5 min are saved.
- **Recording**: start and stop a full recording with a hotkey or button.
- **Fullscreen monitor capture**: pick the monitor on a visual layout of your displays.
- **Choose your games**: Minecraft (Java, Bedrock, Lunar, Badlion, Feather…) is preset. Any running program can be added. The buffer can switch on automatically when a game starts.
- **Any button can clip**: keyboard keys with Ctrl/Alt/Shift, or **Mouse 4 / Mouse 5 / middle click**. Hotkeys work while the game has focus.
- **Quality and FPS control**: 30–240 fps, native/1440p/1080p/720p, constant-quality or bitrate, and NVIDIA NVENC / AMD AMF / Intel Quick Sync / CPU x264.
- **Separate audio tracks**: every clip contains **Mix · Minecraft · Discord · Microphone** (and optionally all desktop audio) as separate tracks.
- **Wave Editor**:
  - Timeline with thumbnails and one waveform lane per audio track.
  - Split, cut and trim, plus per-piece speed (0.25× slow-mo up to 4×).
  - Per-track volume, mute, solo and sync nudge.
  - Glowing text overlays, 9:16 vertical crop for TikTok/Shorts, and colour looks (Vibrant, Cinematic, Wave, Warm, B&W).
  - Vignette, sharpen and fades.
  - Undo and redo.
  - Export to **Discord's 10 MB / 50 MB limits**, YouTube 1080p60, Shorts or GIF.
- **Clip library**:
  - Thumbnails, filters per game, favourites, search and sort.
  - Rename, recycle-bin delete.
  - **Copy (paste straight into Discord)**.
- Runs from the tray, can start with Windows, shows an overlay popup and a sound when a clip saves, and never steals focus from your game.

## Download & run

1. Open the repository's **Actions** tab, go to **Build WaveClips**, pick the latest run and download the **WaveClips-win-x64** artifact.
2. Unzip it anywhere, for example `C:\Games\WaveClips`, and run **WaveClips.exe**. FFmpeg (`ffmpeg.exe` / `ffprobe.exe`) is included.
3. Windows SmartScreen may warn because the exe isn't code-signed. Choose **More info → Run anyway**.

If `ffmpeg.exe` is missing, WaveClips offers a one-click download under **Settings → General**.

**Requirements:** Windows 10 version 2004 or newer, or Windows 11 (64-bit). Per-app audio capture needs 2004+. A GPU with a hardware encoder is recommended; any NVIDIA GTX 900+/RTX, AMD RX 400+ or Intel 6th gen+ works. The CPU encoder works everywhere.

## Default hotkeys

| Action | Default |
| --- | --- |
| Save clip | **F8** |
| Start / stop recording | **F9** |
| Replay buffer on / off | **Alt + F10** |

Change them in **Settings → Hotkeys**: click a box and press a key combo or a mouse button.

## How the separate audio tracks work

WaveClips uses Windows' *process loopback* capture. It records the sound of **one program and its child processes** without any virtual cables:

| Track | Source |
| --- | --- |
| **Mix (all)** | Everything below mixed together. It comes first, so Discord, browsers and normal players play everything. |
| **Minecraft** | The detected game's process only (javaw.exe / Minecraft.Windows.exe). |
| **Discord** | Discord.exe, including Discord PTB and Canary. Only your friends' voices, soundboard, etc. |
| **Microphone** | Your mic, or whichever input device you choose. |
| *System (off by default)* | All desktop audio. |

Add, rename, recolour or retarget tracks (up to 6) in **Settings → Audio Tracks**. The **Live test** button shows a level meter for each track.

Every track is produced by one clock-driven audio engine, so the tracks never drift apart. They're lined up with the video's first captured frame. If audio ever feels early or late, use **Audio sync offset**.

In the **Wave Editor** each track gets its own volume, mute and solo, so you can lower Discord, raise the game and export. Other editors (Premiere, DaVinci Resolve, Vegas, OBS) also see the separate tracks. In VLC, use *Audio → Audio Track*.

## Wave Editor shortcuts

| Key | Action |
| --- | --- |
| Space | Play / pause |
| S | Split at playhead |
| Delete | Cut / restore the selected piece |
| I / O | Trim start / end to playhead |
| T | Add text |
| F | Render this frame with all effects |
| ← / → | Previous / next frame (hold Shift for 1 s) |
| Ctrl + Z / Ctrl + Y | Undo / redo |
| Ctrl + mouse wheel | Zoom the timeline |

Other timeline actions:

- Drag the cyan edges of the clip to trim.
- Drag the white lines between pieces to move cuts.
- Right-click a piece for speed options.
- Drag text bars to move them in time, and drag text or the 9:16 frame in the preview to position them.

Edits are saved automatically, so reopening the clip brings them back. Exports go next to the original clip and appear in the library.

## How capture works

```
 ddagrab (GPU Desktop Duplication, falls back to GDI)  ─┐
 Minecraft ─ process loopback ─┐                        │
 Discord   ─ process loopback ─┼─ jitter buffers ─ 48 kHz pacer ─ named pipes ─┤ ffmpeg ─ 1-second .ts segments
 Mic       ─ WASAPI          ─┘      (one clock)          (+ Mix track)         │  (NVENC / AMF / QSV / x264)
                                                                                ┘
 Clip key → segments covering the last N seconds are joined → MP4 with named audio tracks
```

- The buffer lives in `%LOCALAPPDATA%\WaveClips\buffer`, which you can change under Settings → Clipping. Old segments are deleted continuously.
- If a capture mode fails (for example zero-copy GPU encode on a hybrid-GPU laptop), WaveClips falls back through: Desktop Duplication + GPU encode → Desktop Duplication + download → GDI → GDI + x264.
- Settings are stored in `%APPDATA%\WaveClips\settings.json`. The log is in `%LOCALAPPDATA%\WaveClips\waveclips.log`.

## Troubleshooting

- **"Capture failed to start"**: the popup and the log show FFmpeg's error. Try **Settings → Capture → Encoder → Auto**, or the GDI capture method.
- **A track is silent**: the track row shows its status, for example "Discord isn't running". Use **Pick app ▾** to choose the right program. Per-app capture needs Windows 10 2004+.
- **Editor preview says it's converting**: this happens for HEVC clips when Windows lacks the HEVC codec. The export is unaffected.
- **Hotkey doesn't work in one specific game**: games running as administrator need WaveClips to run as administrator too.

## Building from source

Needs the .NET 8 SDK.

```bash
# Windows app (also compiles on Linux/macOS thanks to EnableWindowsTargeting)
dotnet publish waveclips/WaveClips/WaveClips.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o out

# Pipeline tests: run the exact FFmpeg command lines WaveClips generates (Linux, needs ffmpeg + ffprobe)
FFMPEG_EXE=/path/to/ffmpeg dotnet run -c Release --project waveclips/Tests/WaveClips.PipelineTests
```

The pipeline tests cover:

- The real capture command, with `testsrc2` standing in for `ddagrab` and FIFOs for the named pipes.
- 1-second segmenting, clip saving and segment cleanup.
- That **each audio track contains only its own source**.
- Editor exports with cuts, speed ramps, per-track volume/offset, text, 9:16 crop, looks, fades, size targets and GIF.

The Windows CI job also runs **`WaveClips.exe --selftest <folder>`**. With `WAVECLIPS_HOME` pointing at a scratch folder, it:

- Screenshots every page.
- Starts the replay buffer with both capture methods.
- Saves a clip and checks all its named audio tracks.
- Records, shows the popup, and opens and exports a clip in the editor.

The report, log and screenshots are uploaded as the **WaveClips-selftest** artifact.

| Folder | What's inside |
| --- | --- |
| `WaveClips/Core` | Settings, FFmpeg, encoders, monitors, game detection, hotkeys, clip library |
| `WaveClips/Audio` | Per-app / mic capture, jitter buffers, pacer, named-pipe writers |
| `WaveClips/Capture` | FFmpeg command builder, capture session, replay buffer, clip writer |
| `WaveClips/Editor` | Edit model, timeline, multi-track preview player, export builder |
| `WaveClips/Views`, `Themes`, `Controls` | WaveClient-styled UI |
| `WaveClips/Interop` | WASAPI process loopback, DXGI, low-level hooks |
