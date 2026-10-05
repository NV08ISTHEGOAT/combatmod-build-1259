<#
    WaveOptimizer - Windows gaming / system optimizer
    -------------------------------------------------
    * Reads your real hardware and tells you what is actually holding your FPS back.
    * Every tweak is a switch: flip it, hit APPLY. Flip it back + APPLY to undo.
    * Every registry value / service it touches is backed up first to
      %ProgramData%\WaveOptimizer\backup.json so "Revert All" restores your original settings.

    Run WaveOptimizer.exe, or WaveOptimizer.bat for the script version (both elevate to admin).
#>

param(
    [switch]$SelfTest,   # CI: load the UI, scan hardware, check every tweak (read-only), exit
    [switch]$RoundTrip   # CI: also apply + revert every non-network tweak (throwaway VMs only!)
)

#region ---------- bootstrap: STA + admin ----------
$ErrorActionPreference = 'Stop'
$script:Version  = '1.1.0'
$script:Headless = $SelfTest -or $RoundTrip

$isAdmin = (New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
$isSta = [Threading.Thread]::CurrentThread.GetApartmentState() -eq 'STA'

Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase

if (-not $isAdmin -or -not $isSta) {
    if (-not $PSCommandPath) {
        # running as the compiled WaveOptimizer.exe (its manifest normally asks for admin itself)
        [void][Windows.MessageBox]::Show('WaveOptimizer needs administrator rights. Right-click WaveOptimizer.exe and choose "Run as administrator".', 'WaveOptimizer', 'OK', 'Warning')
        exit 1
    }
    $verb = if ($isAdmin) { 'Open' } else { 'RunAs' }
    Start-Process -FilePath 'powershell.exe' -Verb $verb `
        -ArgumentList "-NoProfile -ExecutionPolicy Bypass -STA -File `"$PSCommandPath`""
    exit
}

if (-not $script:Headless) {
    # Hide the console window behind the GUI
    Add-Type -Namespace Wave -Name Native -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern IntPtr GetConsoleWindow();
[DllImport("user32.dll")]   public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
'@
    [void][Wave.Native]::ShowWindow([Wave.Native]::GetConsoleWindow(), 0)
}
#endregion

#region ---------- backup store ----------
$script:DataDir    = Join-Path $env:ProgramData 'WaveOptimizer'
$script:BackupFile = Join-Path $script:DataDir 'backup.json'
$script:Backup     = @{}
New-Item -ItemType Directory -Path $script:DataDir -Force | Out-Null

if (Test-Path $script:BackupFile) {
    try {
        $json = Get-Content $script:BackupFile -Raw | ConvertFrom-Json
        foreach ($p in $json.PSObject.Properties) {
            $script:Backup[$p.Name] = @{ Existed = $p.Value.Existed; Value = $p.Value.Value; Type = $p.Value.Type }
        }
    } catch { $script:Backup = @{} }
}

function Save-Backup {
    $script:Backup | ConvertTo-Json -Depth 5 | Set-Content -Path $script:BackupFile -Encoding UTF8
}

function Get-RegValue($Path, $Name) {
    try { (Get-ItemProperty -Path $Path -Name $Name -ErrorAction Stop).$Name } catch { $null }
}

# Write a registry value, remembering the ORIGINAL value the first time we touch it.
function Set-Reg($Path, $Name, $Value, $Type = 'DWord') {
    $key = "$Path|$Name"
    if (-not $script:Backup.ContainsKey($key)) {
        $entry = @{ Existed = $false; Value = $null; Type = $null }
        if (Test-Path $Path) {
            $item = Get-Item -Path $Path
            if ($item.GetValueNames() -contains $Name) {
                $entry.Existed = $true
                $entry.Value   = $item.GetValue($Name, $null, 'DoNotExpandEnvironmentNames')
                $entry.Type    = $item.GetValueKind($Name).ToString()
            }
        }
        $script:Backup[$key] = $entry
        Save-Backup
    }
    if (-not (Test-Path $Path)) { New-Item -Path $Path -Force | Out-Null }
    New-ItemProperty -Path $Path -Name $Name -Value $Value -PropertyType $Type -Force | Out-Null
}

# Put a registry value back to exactly what it was before WaveOptimizer touched it.
function Restore-Reg($Path, $Name) {
    $key = "$Path|$Name"
    if (-not $script:Backup.ContainsKey($key)) { return }
    $b = $script:Backup[$key]
    if ($b.Existed) {
        $val = $b.Value
        if ($b.Type -eq 'Binary')      { $val = [byte[]]$val }
        if ($b.Type -eq 'MultiString') { $val = [string[]]$val }
        if (-not (Test-Path $Path)) { New-Item -Path $Path -Force | Out-Null }
        New-ItemProperty -Path $Path -Name $Name -Value $val -PropertyType $b.Type -Force | Out-Null
    } elseif (Test-Path $Path) {
        Remove-ItemProperty -Path $Path -Name $Name -ErrorAction SilentlyContinue
    }
    $script:Backup.Remove($key)
    Save-Backup
}
#endregion

#region ---------- helpers for non-registry tweaks ----------
$script:Build = [int](Get-RegValue 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion' 'CurrentBuild')
$script:IsWin11 = $script:Build -ge 22000
$script:RamKB = [int]((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1KB)
$script:AllTasks = @()
$script:NetAdapters = @()

function Get-ActiveSchemeGuid {
    $out = powercfg /getactivescheme
    if ("$out" -match '([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})') { $Matches[1] }
}

function Test-PerfPlan {
    $g = Get-ActiveSchemeGuid
    if ($g -in @('8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c', 'e9a42b02-d5df-448d-aa00-03f14749eb61')) { return $true }
    if ($script:Backup.ContainsKey('ultimate') -and $script:Backup['ultimate'].Value -eq $g) { return $true }
    "$(powercfg /getactivescheme)" -match 'Ultimate|High performance|Ultieme|Hoge prestaties|Hohe Leistung|Performances|Alto rendimiento'
}

# Language-independent read of a power setting: the last two hex values printed are AC then DC.
function Get-PwrAc($Sub, $Set) {
    $out = powercfg /query SCHEME_CURRENT $Sub $Set
    if ($LASTEXITCODE -ne 0) { return $null }
    $hex = @([regex]::Matches(($out -join "`n"), ':\s*0x([0-9a-fA-F]+)') | ForEach-Object { $_.Groups[1].Value })
    if ($hex.Count -lt 2) { return $null }
    [Convert]::ToInt64($hex[$hex.Count - 2], 16)
}

function Get-NetInterfaceKeys {
    Get-ChildItem 'HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces' -ErrorAction SilentlyContinue |
        Where-Object {
            $p = Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue
            $p.DhcpIPAddress -or ($p.IPAddress -and "$($p.IPAddress)" -ne '0.0.0.0')
        } |
        ForEach-Object { 'HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\' + $_.PSChildName }
}

function Test-ServiceExists($Name) { Test-Path "HKLM:\SYSTEM\CurrentControlSet\Services\$Name" }

function Set-ServiceStart($Name, $StartValue) {
    $path = "HKLM:\SYSTEM\CurrentControlSet\Services\$Name"
    if (-not (Test-Path $path)) { return }
    Set-Reg $path 'Start' $StartValue
    if ($StartValue -eq 4) { Stop-Service -Name $Name -Force -ErrorAction SilentlyContinue }
}

function Restore-ServiceStart($Name) {
    $path = "HKLM:\SYSTEM\CurrentControlSet\Services\$Name"
    Restore-Reg $path 'Start'
    if ((Get-RegValue $path 'Start') -eq 2) { Start-Service -Name $Name -ErrorAction SilentlyContinue }
}

function Get-TaskMatches($Specs) {
    foreach ($spec in $Specs) { $script:AllTasks | Where-Object { ($_.TaskPath + $_.TaskName) -like $spec } }
}

function Get-NicProps($Keyword) {
    foreach ($a in $script:NetAdapters) {
        $p = Get-NetAdapterAdvancedProperty -Name $a.Name -RegistryKeyword $Keyword -ErrorAction SilentlyContinue
        if ($p) { [pscustomobject]@{ Adapter = $a; Prop = $p; Keyword = $Keyword } }
    }
}

function Backup-Special($Key, $Value) {
    if (-not $script:Backup.ContainsKey($Key)) { $script:Backup[$Key] = @{ Existed = $true; Value = $Value; Type = 'Special' }; Save-Backup }
}

function Pop-Special($Key) {
    if (-not $script:Backup.ContainsKey($Key)) { return $null }
    $v = $script:Backup[$Key]; $script:Backup.Remove($Key); Save-Backup
    $v
}

function RegV($P, $N, $V, $T = 'DWord') { @{ P = $P; N = $N; V = $V; T = $T } }

function SvcT($Id, $Name, $Risk, $Presets, $Svc, $Desc, $Start = 4) {
    @{ Id = $Id; Cat = 'Services'; Name = $Name; Risk = $Risk; Presets = $Presets; Svc = $Svc; SvcStart = $Start; Desc = $Desc }
}

function TaskT($Id, $Name, $Risk, $Presets, $Tasks, $Desc) {
    @{ Id = $Id; Cat = 'Scheduled Tasks'; Name = $Name; Risk = $Risk; Presets = $Presets; Tasks = $Tasks; Desc = $Desc }
}
#endregion

#region ---------- TWEAK CATALOGUE ----------
# Each tweak can combine any of these parts; WaveOptimizer applies, backs up, reverts and checks them generically:
#   Reg   = registry values            Svc   = services to set to SvcStart (default 4 = Disabled)
#   Tasks = scheduled task wildcards   Pwr   = powercfg settings (AC / plugged in)
#   Nic   = network adapter keywords   MMA   = Memory Manager agent feature to disable
#   Apply / Revert / Check             = custom script blocks     Applies = when to show it
# Presets: S = Safe, G = Gaming, X = Extreme. Risk: Safe / Moderate / Advanced.
$ADV  = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced'
$CDM  = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager'
$PW   = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows'
$MM   = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile'
$GC   = 'HKCU:\System\GameConfigStore'
$GB   = 'HKCU:\Software\Microsoft\GameBar'
$EDGE = 'HKLM:\SOFTWARE\Policies\Microsoft\Edge'
$MEMK = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Memory Management'
$FSK  = 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem'
$CS   = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore'
$GFX  = 'HKLM:\SYSTEM\CurrentControlSet\Control\GraphicsDrivers'
$DESK = 'HKCU:\Control Panel\Desktop'
$USB  = '2a737441-1930-4402-8d77-b2bebba308a3'
$DXK  = 'HKCU:\Software\Microsoft\DirectX\UserGpuPreferences'

$script:Tweaks = @(
    # ============================== GAMING ==============================
    @{ Id='gamemode'; Cat='Gaming'; Name='Windows Game Mode'; Risk='Safe'; Presets='SGX'
       Desc='Tells Windows to prioritise the game you are playing and pause Windows Update installs while you play.'
       Reg=@((RegV $GB 'AllowAutoGameMode' 1), (RegV $GB 'AutoGameModeEnabled' 1)) },
    @{ Id='gamedvr'; Cat='Gaming'; Name='Disable Xbox Game DVR background recording'; Risk='Safe'; Presets='SGX'
       Desc='Stops Windows silently recording gameplay in the background. Frees GPU encoder time and a bit of FPS.'
       Reg=@((RegV $GC 'GameDVR_Enabled' 0), (RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR' 'AppCaptureEnabled' 0),
             (RegV "$PW\GameDVR" 'AllowGameDVR' 0)) },
    @{ Id='dvrcapture'; Cat='Gaming'; Name='Disable instant-replay buffer + audio capture'; Risk='Safe'; Presets='SGX'
       Desc='Turns off the "record what happened" buffer and background audio capture used by Game Bar.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR' 'HistoricalCaptureEnabled' 0),
             (RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR' 'AudioCaptureEnabled' 0)) },
    @{ Id='gamebarbtn'; Cat='Gaming'; Name='Xbox controller button does not open Game Bar'; Risk='Safe'; Presets='GX'
       Desc='Stops the Game Bar overlay popping up over your game when you press the Xbox/guide button.'
       Reg=@((RegV $GB 'UseNexusForGameBarEnabled' 0)) },
    @{ Id='gamebartips'; Cat='Gaming'; Name='Hide Game Bar startup tips'; Risk='Safe'; Presets='SGX'
       Desc='No more "Press Win+G to open Game Bar" popup when a game starts.'
       Reg=@((RegV $GB 'ShowStartupPanel' 0)) },
    @{ Id='gamesprio'; Cat='Gaming'; Name='Boost game CPU/GPU scheduling priority'; Risk='Safe'; Presets='GX'
       Desc='Raises the Multimedia Class Scheduler "Games" profile: higher GPU priority, high scheduling category.'
       Reg=@((RegV "$MM\Tasks\Games" 'GPU Priority' 8), (RegV "$MM\Tasks\Games" 'Priority' 6),
             (RegV "$MM\Tasks\Games" 'Scheduling Category' 'High' 'String'), (RegV "$MM\Tasks\Games" 'SFIO Priority' 'High' 'String'),
             (RegV $MM 'SystemResponsiveness' 10)) },
    @{ Id='foreground'; Cat='Gaming'; Name='Favor foreground app (short CPU quantum)'; Risk='Safe'; Presets='GX'
       Desc='Win32PrioritySeparation = 0x26: the window you are focused on gets longer, more frequent CPU slices.'
       Reg=@((RegV 'HKLM:\SYSTEM\CurrentControlSet\Control\PriorityControl' 'Win32PrioritySeparation' 38)) },
    @{ Id='windowedopt'; Cat='Gaming'; Name='Optimizations for windowed games + VRR'; Risk='Safe'; Presets='GX'; Applies={ $script:IsWin11 }
       Desc='Upgrades DX10/11 borderless-window games to the low-latency flip model and enables VRR (G-Sync/FreeSync) in windowed mode.'
       Apply={
           $cur = "$(Get-RegValue $DXK 'DirectXUserGlobalSettings')"
           $map = [ordered]@{}
           foreach ($pair in ($cur -split ';')) { if ($pair -match '^(.+?)=(.*)$') { $map[$Matches[1]] = $Matches[2] } }
           $map['SwapEffectUpgradeEnable'] = '1'; $map['VRROptimizeEnable'] = '1'
           Set-Reg $DXK 'DirectXUserGlobalSettings' ((($map.Keys | ForEach-Object { "$_=$($map[$_])" }) -join ';') + ';') 'String'
       }
       Revert={ Restore-Reg $DXK 'DirectXUserGlobalSettings' }
       Check={ $v = "$(Get-RegValue $DXK 'DirectXUserGlobalSettings')"; $v -match 'SwapEffectUpgradeEnable=1' -and $v -match 'VRROptimizeEnable=1' } },
    @{ Id='fso'; Cat='Gaming'; Name='Disable fullscreen optimizations (global)'; Risk='Advanced'; Presets=''
       Desc='Forces true exclusive fullscreen in older DX9-11 games. Can cut input lag in some titles, but breaks fast Alt-Tab/overlays in others. Test, revert if worse.'
       Reg=@((RegV $GC 'GameDVR_FSEBehaviorMode' 2), (RegV $GC 'GameDVR_HonorUserFSEBehaviorMode' 1),
             (RegV $GC 'GameDVR_FSEBehavior' 2), (RegV $GC 'GameDVR_DXGIHonorFSEWindowsCompatible' 1)) },
    @{ Id='timerres'; Cat='Gaming'; Name='Allow global timer resolution requests'; Risk='Advanced'; Presets='X'; Reboot=$true; Applies={ $script:IsWin11 }
       Desc='Windows 11 ignores high-precision timer requests from games that are minimized/covered. This restores the old behaviour for steadier frame pacing; slightly higher idle power.'
       Reg=@((RegV 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\kernel' 'GlobalTimerResolutionRequests' 1)) },

    # ============================== INPUT ==============================
    @{ Id='mouseaccel'; Cat='Mouse & Keyboard'; Name='Disable mouse acceleration'; Risk='Safe'; Presets='GX'
       Desc='Turns off "Enhance pointer precision" so aim is 1:1 with your hand movement.'
       Reg=@((RegV 'HKCU:\Control Panel\Mouse' 'MouseSpeed' '0' 'String'), (RegV 'HKCU:\Control Panel\Mouse' 'MouseThreshold1' '0' 'String'),
             (RegV 'HKCU:\Control Panel\Mouse' 'MouseThreshold2' '0' 'String')) },
    @{ Id='stickykeys'; Cat='Mouse & Keyboard'; Name='Disable Sticky Keys shortcut (Shift x5)'; Risk='Safe'; Presets='SGX'
       Desc='No more Sticky Keys popup tabbing you out of a game when you spam Shift.'
       Reg=@((RegV 'HKCU:\Control Panel\Accessibility\StickyKeys' 'Flags' '506' 'String')) },
    @{ Id='filterkeys'; Cat='Mouse & Keyboard'; Name='Disable Filter Keys shortcut (hold Right Shift)'; Risk='Safe'; Presets='SGX'
       Desc='Stops the Filter Keys popup when you hold Right Shift for 8 seconds.'
       Reg=@((RegV 'HKCU:\Control Panel\Accessibility\Keyboard Response' 'Flags' '122' 'String')) },
    @{ Id='togglekeys'; Cat='Mouse & Keyboard'; Name='Disable Toggle Keys shortcut (hold Num Lock)'; Risk='Safe'; Presets='SGX'
       Desc='Stops the Toggle Keys popup when you hold Num Lock for 5 seconds.'
       Reg=@((RegV 'HKCU:\Control Panel\Accessibility\ToggleKeys' 'Flags' '58' 'String')) },
    @{ Id='langhotkey'; Cat='Mouse & Keyboard'; Name='Disable Alt+Shift keyboard-layout switching'; Risk='Safe'; Presets='GX'
       Desc='Stops Alt+Shift / Ctrl+Shift silently switching your keyboard layout mid-game (WASD suddenly becomes ZQSD).'
       Reg=@((RegV 'HKCU:\Keyboard Layout\Toggle' 'Hotkey' '3' 'String'), (RegV 'HKCU:\Keyboard Layout\Toggle' 'Language Hotkey' '3' 'String'),
             (RegV 'HKCU:\Keyboard Layout\Toggle' 'Layout Hotkey' '3' 'String')) },
    @{ Id='keyrepeat'; Cat='Mouse & Keyboard'; Name='Fastest keyboard repeat rate'; Risk='Safe'; Presets='GX'
       Desc='Shortest repeat delay and fastest repeat speed. Takes effect after sign-out.'
       Reg=@((RegV 'HKCU:\Control Panel\Keyboard' 'KeyboardDelay' '0' 'String'), (RegV 'HKCU:\Control Panel\Keyboard' 'KeyboardSpeed' '31' 'String')) },
    @{ Id='hovertime'; Cat='Mouse & Keyboard'; Name='Faster mouse-hover response'; Risk='Safe'; Presets='GX'
       Desc='Tooltips and hover previews appear after 10 ms instead of 400 ms.'
       Reg=@((RegV 'HKCU:\Control Panel\Mouse' 'MouseHoverTime' '10' 'String')) },
    @{ Id='inkworkspace'; Cat='Mouse & Keyboard'; Name='Disable Windows Ink Workspace'; Risk='Safe'; Presets='GX'
       Desc='Stops pen/tablet buttons popping up the Ink Workspace (osu! and drawing-tablet players).'
       Reg=@((RegV 'HKLM:\SOFTWARE\Policies\Microsoft\WindowsInkWorkspace' 'AllowWindowsInkWorkspace' 0)) },

    # ============================== GPU & DISPLAY ==============================
    @{ Id='hags'; Cat='GPU & Display'; Name='Hardware-accelerated GPU scheduling'; Risk='Safe'; Presets='GX'; Reboot=$true
       Desc='Lets the GPU manage its own memory queue. Lower latency on GTX 10-series / RX 5000 and newer. Needs a reboot.'
       Reg=@((RegV $GFX 'HwSchMode' 2)) },
    @{ Id='mpo'; Cat='GPU & Display'; Name='Disable Multi-Plane Overlay (MPO)'; Risk='Advanced'; Presets=''; Reboot=$true
       Desc='NVIDIA-documented fix for flicker, black screens and stutter with multiple monitors or video playing on a second screen. Only use if you have those problems.'
       Reg=@((RegV 'HKLM:\SOFTWARE\Microsoft\Windows\Dwm' 'OverlayTestMode' 5)) },
    @{ Id='tdr'; Cat='GPU & Display'; Name='Longer GPU timeout before driver reset (TDR)'; Risk='Moderate'; Presets='X'; Reboot=$true
       Desc='Gives the GPU 10 s instead of 2 s before Windows resets the driver. Fixes "display driver stopped responding" crashes during shader compilation.'
       Reg=@((RegV $GFX 'TdrDelay' 10)) },
    @{ Id='adaptbright'; Cat='GPU & Display'; Name='Disable adaptive brightness (plugged in)'; Risk='Safe'; Presets='GX'
       Desc='Stops the screen dimming/brightening based on content or light sensor while gaming.'
       Pwr=@(@{ Sub='SUB_VIDEO'; Set='ADAPTBRIGHT'; V=0 }) },
    @{ Id='nvtelemetry'; Cat='GPU & Display'; Name='Disable NVIDIA telemetry service'; Risk='Safe'; Presets='GX'
       Desc='Turns off NvTelemetryContainer (older NVIDIA drivers). Does not affect the driver or control panel.'
       Svc=@('NvTelemetryContainer') },

    # ============================== POWER & CPU ==============================
    @{ Id='powerplan'; Cat='Power & CPU'; Name='Ultimate Performance power plan'; Risk='Safe'; Presets='GX'
       Desc='Unlocks and activates the hidden Ultimate Performance plan (falls back to High Performance). CPU stops downclocking between frames.'
       Apply={
           Backup-Special 'powerplan' (Get-ActiveSchemeGuid)
           $list = "$(powercfg /list)"; $guid = $null
           if ($script:Backup.ContainsKey('ultimate') -and $list -match $script:Backup['ultimate'].Value) { $guid = $script:Backup['ultimate'].Value }
           if (-not $guid) {
               $dup = powercfg -duplicatescheme e9a42b02-d5df-448d-aa00-03f14749eb61
               if ("$dup" -match '([0-9a-fA-F-]{36})') { $guid = $Matches[1]; $script:Backup['ultimate'] = @{ Existed=$true; Value=$guid; Type='Special' }; Save-Backup }
           }
           if (-not $guid) { $guid = '8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c'; Write-Log 'Ultimate plan not available on this PC - using High Performance.' }
           powercfg /setactive $guid | Out-Null
       }
       Revert={ $b = Pop-Special 'powerplan'; if ($b) { powercfg /setactive $b.Value | Out-Null } }
       Check={ Test-PerfPlan } },
    @{ Id='powerthrottle'; Cat='Power & CPU'; Name='Disable power throttling'; Risk='Moderate'; Presets='GX'
       Desc='Stops Windows pushing background/game threads onto slow efficiency states. Uses more battery on laptops.'
       Reg=@((RegV 'HKLM:\SYSTEM\CurrentControlSet\Control\Power\PowerThrottling' 'PowerThrottlingOff' 1)) },
    @{ Id='coreparking'; Cat='Power & CPU'; Name='Disable CPU core parking'; Risk='Moderate'; Presets='X'
       Desc='Keeps 100% of cores unparked when plugged in. Can smooth out stutter on older Intel/AMD CPUs.'
       Pwr=@(@{ Sub='SUB_PROCESSOR'; Set='CPMINCORES'; V=100 }) },
    @{ Id='procmin'; Cat='Power & CPU'; Name='Minimum processor state 100%'; Risk='Moderate'; Presets='X'
       Desc='CPU never clocks down when plugged in - no ramp-up delay when a heavy frame arrives. More heat and power at idle.'
       Pwr=@(@{ Sub='SUB_PROCESSOR'; Set='PROCTHROTTLEMIN'; V=100 }) },
    @{ Id='boostmode'; Cat='Power & CPU'; Name='Aggressive CPU turbo boost'; Risk='Moderate'; Presets='GX'
       Desc='Processor performance boost mode = Aggressive: turbo kicks in sooner and harder.'
       Pwr=@(@{ Sub='SUB_PROCESSOR'; Set='PERFBOOSTMODE'; V=2 }) },
    @{ Id='epp'; Cat='Power & CPU'; Name='Energy-performance preference: max performance'; Risk='Moderate'; Presets='GX'
       Desc='Tells modern CPUs (Intel Speed Shift / AMD CPPC) to always favour speed over power saving when plugged in.'
       Pwr=@(@{ Sub='SUB_PROCESSOR'; Set='PERFEPP'; V=0 }) },
    @{ Id='cooling'; Cat='Power & CPU'; Name='Active cooling policy'; Risk='Safe'; Presets='GX'
       Desc='Spin the fans up before slowing the CPU down, instead of throttling first.'
       Pwr=@(@{ Sub='SUB_PROCESSOR'; Set='SYSCOOLPOL'; V=1 }) },
    @{ Id='aspm'; Cat='Power & CPU'; Name='Disable PCIe link-state power saving'; Risk='Safe'; Presets='GX'
       Desc='Keeps the PCIe link to your GPU and NVMe drive at full power - removes wake-up latency.'
       Pwr=@(@{ Sub='SUB_PCIEXPRESS'; Set='ASPM'; V=0 }) },
    @{ Id='diskidle'; Cat='Power & CPU'; Name='Never power down drives'; Risk='Safe'; Presets='GX'
       Desc='Stops hard drives spinning down when plugged in (no 2-second hitch when a game loads from it).'
       Pwr=@(@{ Sub='SUB_DISK'; Set='DISKIDLE'; V=0 }) },
    @{ Id='usbsuspend'; Cat='Power & CPU'; Name='Disable USB selective suspend'; Risk='Safe'; Presets='GX'
       Desc='Stops Windows putting your mouse/keyboard/headset USB ports to sleep (fixes random input lag spikes).'
       Pwr=@(@{ Sub=$USB; Set='48e6b7a6-50f5-4782-a5d4-53bb8f07e226'; V=0 }) },
    @{ Id='usb3lpm'; Cat='Power & CPU'; Name='Disable USB 3 link power management'; Risk='Safe'; Presets='GX'
       Desc='Keeps USB 3 links fully awake - fixes dropouts on USB audio interfaces, capture cards and VR headsets.'
       Pwr=@(@{ Sub=$USB; Set='d4e98f31-5ffe-4ce1-be31-1b38b384c009'; V=0 }) },
    @{ Id='wifipower'; Cat='Power & CPU'; Name='Wi-Fi adapter: maximum performance'; Risk='Safe'; Presets='GX'
       Desc='Disables Wi-Fi power saving when plugged in. Fixes periodic ping spikes on wireless.'
       Pwr=@(@{ Sub='19cad1df-c1c9-4d58-9f87-d6fca8b31ec8'; Set='12bbebe6-58d6-4636-95bb-3217ef867c1a'; V=0 }) },
    @{ Id='fastboot'; Cat='Power & CPU'; Name='Disable Fast Startup'; Risk='Safe'; Presets='GX'
       Desc='Shut down really shuts down. Fast Startup keeps the kernel hibernated, so driver problems and memory leaks survive "restarts".'
       Reg=@((RegV 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power' 'HiberbootEnabled' 0)) },
    @{ Id='hibernate'; Cat='Power & CPU'; Name='Disable hibernation'; Risk='Moderate'; Presets='X'
       Desc='Deletes hiberfil.sys (frees disk space equal to ~40-100% of your RAM). You lose the Hibernate option.'
       Apply={ Backup-Special 'hibernate' ((Get-RegValue 'HKLM:\SYSTEM\CurrentControlSet\Control\Power' 'HibernateEnabled') -ne 0); powercfg /hibernate off | Out-Null }
       Revert={ $b = Pop-Special 'hibernate'; if ($b -and $b.Value) { powercfg /hibernate on | Out-Null } }
       Check={ (Get-RegValue 'HKLM:\SYSTEM\CurrentControlSet\Control\Power' 'HibernateEnabled') -eq 0 } },

    # ============================== MEMORY & STORAGE ==============================
    @{ Id='sysmain'; Cat='Memory & Storage'; Name='Disable SysMain (Superfetch)'; Risk='Moderate'; Presets='X'
       Desc='Stops RAM preloading and disk churn. Helps on HDDs and low-RAM PCs; on a fast SSD with 16 GB+ leave it ON.'
       Svc=@('SysMain') },
    @{ Id='wsearch'; Cat='Memory & Storage'; Name='Disable Windows Search indexer'; Risk='Moderate'; Presets='X'
       Desc='Stops background file indexing. Start-menu file search gets slower in exchange.'
       Svc=@('WSearch') },
    @{ Id='pagingexec'; Cat='Memory & Storage'; Name='Keep kernel in RAM (DisablePagingExecutive)'; Risk='Moderate'; Presets='X'; Reboot=$true
       Desc='Kernel and drivers are never paged to disk. Only worthwhile with 16 GB+ RAM.'
       Reg=@((RegV $MEMK 'DisablePagingExecutive' 1)) },
    @{ Id='memcompress'; Cat='Memory & Storage'; Name='Disable memory compression'; Risk='Moderate'; Presets='X'; Reboot=$true
       Desc='Saves the CPU time spent compressing RAM. Only use with 16 GB+ - on low-RAM PCs compression is faster than the page file.'
       MMA='MemoryCompression' },
    @{ Id='pagecombine'; Cat='Memory & Storage'; Name='Disable memory page combining'; Risk='Moderate'; Presets='X'
       Desc='Stops Windows periodically scanning RAM for duplicate pages to merge.'
       MMA='PageCombining' },
    @{ Id='prelaunch'; Cat='Memory & Storage'; Name='Disable app pre-launch'; Risk='Moderate'; Presets='X'
       Desc='Stops Windows pre-loading Store apps it predicts you will open.'
       MMA='ApplicationPreLaunch' },
    @{ Id='svchost'; Cat='Memory & Storage'; Name='Group svchost processes by RAM size'; Risk='Moderate'; Presets='X'; Reboot=$true
       Desc='Sets SvcHostSplitThresholdInKB to your RAM size so Windows runs ~70 svchost.exe instead of ~150. Less overhead, purely cosmetic for some.'
       Reg=@((RegV 'HKLM:\SYSTEM\CurrentControlSet\Control' 'SvcHostSplitThresholdInKB' $script:RamKB)) },
    @{ Id='lastaccess'; Cat='Memory & Storage'; Name='Disable NTFS last-access timestamps'; Risk='Safe'; Presets='GX'
       Desc='Windows stops writing a timestamp every time any file is read. Fewer disk writes when games load thousands of files.'
       Reg=@((RegV $FSK 'NtfsDisableLastAccessUpdate' -2147483647)) },
    @{ Id='8dot3'; Cat='Memory & Storage'; Name='Disable 8.3 short filename creation'; Risk='Safe'; Presets='GX'
       Desc='Stops NTFS creating legacy DOS names (PROGRA~1) for every new file.'
       Reg=@((RegV $FSK 'NtfsDisable8dot3NameCreation' 1)) },
    @{ Id='ntfsmem'; Cat='Memory & Storage'; Name='Larger NTFS memory cache'; Risk='Moderate'; Presets='X'; Reboot=$true
       Desc='Lets NTFS use more RAM for file metadata caching. Helps big game folders and mod packs; 16 GB+ recommended.'
       Reg=@((RegV $FSK 'NtfsMemoryUsage' 2)) },
    @{ Id='foldertype'; Cat='Memory & Storage'; Name='Disable folder-type auto-discovery'; Risk='Safe'; Presets='SGX'
       Desc='Explorer stops scanning folder contents to guess "Pictures/Music" layouts - big folders open much faster.'
       Reg=@((RegV 'HKCU:\Software\Classes\Local Settings\Software\Microsoft\Windows\Shell\Bags\AllFolders\Shell' 'FolderType' 'NotSpecified' 'String')) },
    @{ Id='reservedstore'; Cat='Memory & Storage'; Name='Disable reserved storage'; Risk='Moderate'; Presets='X'
       Applies={ [bool](Get-Command Get-WindowsReservedStorageState -ErrorAction SilentlyContinue) }
       Desc='Frees the ~7 GB Windows keeps reserved for updates. Updates may need free space later.'
       Apply={ Backup-Special 'reservedstore' "$((Get-WindowsReservedStorageState).ReservedStorageState)"; Set-WindowsReservedStorageState -State Disabled | Out-Null }
       Revert={ $b = Pop-Special 'reservedstore'; if ($b -and $b.Value -ne 'Disabled') { Set-WindowsReservedStorageState -State Enabled | Out-Null } }
       Check={ "$((Get-WindowsReservedStorageState).ReservedStorageState)" -eq 'Disabled' } },
    @{ Id='storagesense'; Cat='Memory & Storage'; Name='Turn on Storage Sense auto-cleanup'; Risk='Safe'; Presets='SGX'
       Desc='Windows automatically deletes temp files and old Recycle Bin items when space runs low.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\StorageSense\Parameters\StoragePolicy' '01' 1)) },

    # ============================== NETWORK ==============================
    @{ Id='netthrottle'; Cat='Network'; Name='Disable network throttling'; Risk='Safe'; Presets='GX'
       Desc='Removes the packet-rate cap Windows applies while multimedia is playing (NetworkThrottlingIndex).'
       Reg=@((RegV $MM 'NetworkThrottlingIndex' -1)) },
    @{ Id='nagle'; Cat='Network'; Name="Disable Nagle's algorithm (lower ping)"; Risk='Safe'; Presets='GX'
       Desc='Sends small game packets immediately instead of bundling them. Can shave a few ms off input-to-server delay.'
       Apply={ foreach ($k in Get-NetInterfaceKeys) { Set-Reg $k 'TcpAckFrequency' 1; Set-Reg $k 'TCPNoDelay' 1 } }
       Revert={ foreach ($k in Get-NetInterfaceKeys) { Restore-Reg $k 'TcpAckFrequency'; Restore-Reg $k 'TCPNoDelay' } }
       Check={ $ks = @(Get-NetInterfaceKeys); $ks.Count -gt 0 -and -not ($ks | Where-Object { (Get-RegValue $_ 'TCPNoDelay') -ne 1 }) } },
    @{ Id='deliveryopt'; Cat='Network'; Name='Stop uploading updates to other PCs'; Risk='Safe'; Presets='SGX'
       Desc='Disables Delivery Optimization peer-to-peer sharing, so Windows does not use your upload bandwidth (and ping) to seed updates.'
       Reg=@((RegV "$PW\DeliveryOptimization" 'DODownloadMode' 0)) },
    @{ Id='dns'; Cat='Network'; Name='Use Cloudflare DNS (1.1.1.1)'; Risk='Moderate'; Presets='X'
       Desc='Faster, private name lookups on your active adapters. Does not lower in-game ping. Revert restores your previous DNS.'
       Applies={ $script:NetAdapters.Count -gt 0 }
       Apply={
           foreach ($a in $script:NetAdapters) {
               $k = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\$($a.InterfaceGuid)"
               if (-not $script:Backup.ContainsKey("$k|NameServer")) {
                   $v = Get-RegValue $k 'NameServer'
                   $script:Backup["$k|NameServer"] = @{ Existed = ($null -ne $v); Value = $v; Type = 'String' }; Save-Backup
               }
               Set-DnsClientServerAddress -InterfaceIndex $a.ifIndex -ServerAddresses '1.1.1.1', '1.0.0.1'
           }
           Clear-DnsClientCache
       }
       Revert={
           foreach ($a in $script:NetAdapters) {
               $k = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\$($a.InterfaceGuid)"
               if (-not $script:Backup.ContainsKey("$k|NameServer")) { continue }
               $old = "$($script:Backup["$k|NameServer"].Value)"
               $script:Backup.Remove("$k|NameServer"); Save-Backup
               if ($old) { Set-DnsClientServerAddress -InterfaceIndex $a.ifIndex -ServerAddresses ($old -split '[, ]+' | Where-Object { $_ }) }
               else { Set-DnsClientServerAddress -InterfaceIndex $a.ifIndex -ResetServerAddresses }
           }
           Clear-DnsClientCache
       }
       Check={ [bool]($script:NetAdapters | Where-Object { (Get-DnsClientServerAddress -InterfaceIndex $_.ifIndex -AddressFamily IPv4).ServerAddresses -contains '1.1.1.1' }) } },
    @{ Id='eee'; Cat='Network'; Name='Disable Energy-Efficient Ethernet'; Risk='Moderate'; Presets='GX'
       Desc='Stops the network card dozing between packets - removes small latency spikes. Network drops for a second when applied.'
       Nic=@{ K=@('*EEE'); V='0' } },
    @{ Id='intmod'; Cat='Network'; Name='Disable interrupt moderation'; Risk='Advanced'; Presets='X'
       Desc='Every packet is handled immediately instead of in batches. Lowest latency, higher CPU use. Network drops for a second.'
       Nic=@{ K=@('*InterruptModeration'); V='0' } },
    @{ Id='flowctrl'; Cat='Network'; Name='Disable Ethernet flow control'; Risk='Moderate'; Presets='X'
       Desc='Stops the switch/router pausing your adapter during congestion. Network drops for a second.'
       Nic=@{ K=@('*FlowControl'); V='0' } },
    @{ Id='lso'; Cat='Network'; Name='Disable Large Send Offload'; Risk='Moderate'; Presets='X'
       Desc='Packets are segmented by Windows instead of the NIC - fixes latency spikes on some Realtek/Intel adapters.'
       Nic=@{ K=@('*LsoV2IPv4', '*LsoV2IPv6'); V='0' } },
    @{ Id='nicpower'; Cat='Network'; Name='Network adapter never sleeps'; Risk='Safe'; Presets='GX'
       Desc='Unticks "Allow the computer to turn off this device to save power" on your active adapters.'
       Applies={ [bool]($script:NetAdapters | ForEach-Object { Get-NetAdapterPowerManagement -Name $_.Name -ErrorAction SilentlyContinue } | Where-Object { "$($_.AllowComputerToTurnOffDevice)" -in 'Enabled', 'Disabled' }) }
       Apply={
           foreach ($a in $script:NetAdapters) {
               $pm = Get-NetAdapterPowerManagement -Name $a.Name -ErrorAction SilentlyContinue
               if (-not $pm -or "$($pm.AllowComputerToTurnOffDevice)" -notin 'Enabled', 'Disabled') { continue }
               Backup-Special "nicpm|$($a.InterfaceGuid)" "$($pm.AllowComputerToTurnOffDevice)"
               Set-NetAdapterPowerManagement -Name $a.Name -AllowComputerToTurnOffDevice Disabled
           }
       }
       Revert={
           foreach ($a in $script:NetAdapters) {
               $b = Pop-Special "nicpm|$($a.InterfaceGuid)"
               if ($b -and $b.Value -eq 'Enabled') { Set-NetAdapterPowerManagement -Name $a.Name -AllowComputerToTurnOffDevice Enabled }
           }
       }
       Check={ -not ($script:NetAdapters | ForEach-Object { Get-NetAdapterPowerManagement -Name $_.Name -ErrorAction SilentlyContinue } | Where-Object { "$($_.AllowComputerToTurnOffDevice)" -eq 'Enabled' }) } },
    @{ Id='llmnr'; Cat='Network'; Name='Disable LLMNR (security)'; Risk='Safe'; Presets='SGX'
       Desc='Turns off a legacy name-lookup protocol that attackers on public Wi-Fi abuse to steal password hashes.'
       Reg=@((RegV 'HKLM:\SOFTWARE\Policies\Microsoft\Windows NT\DNSClient' 'EnableMulticast' 0)) },
    @{ Id='smb1'; Cat='Network'; Name='Disable SMBv1 (security)'; Risk='Safe'; Presets='SGX'
       Desc='Removes the ancient file-sharing protocol used by WannaCry. Modern devices use SMB2/3.'
       Applies={ [bool](Get-Command Get-SmbServerConfiguration -ErrorAction SilentlyContinue) }
       Apply={ Backup-Special 'smb1' ([bool](Get-SmbServerConfiguration).EnableSMB1Protocol); Set-SmbServerConfiguration -EnableSMB1Protocol $false -Force }
       Revert={ $b = Pop-Special 'smb1'; if ($b -and $b.Value) { Set-SmbServerConfiguration -EnableSMB1Protocol $true -Force } }
       Check={ -not (Get-SmbServerConfiguration).EnableSMB1Protocol } },
    @{ Id='wifisense'; Cat='Network'; Name='Disable Wi-Fi Sense hotspot auto-connect'; Risk='Safe'; Presets='SGX'
       Desc='Stops Windows auto-joining open "suggested" hotspots.'
       Reg=@((RegV 'HKLM:\SOFTWARE\Microsoft\PolicyManager\default\WiFi\AllowAutoConnectToWiFiSenseHotspots' 'value' 0),
             (RegV 'HKLM:\SOFTWARE\Microsoft\PolicyManager\default\WiFi\AllowWiFiHotSpotReporting' 'value' 0)) },
    @{ Id='remoteassist'; Cat='Network'; Name='Disable Remote Assistance (security)'; Risk='Safe'; Presets='SGX'
       Desc='Blocks Remote Assistance invitations - a favourite of "tech support" scammers.'
       Reg=@((RegV 'HKLM:\SYSTEM\CurrentControlSet\Control\Remote Assistance' 'fAllowToGetHelp' 0)) },

    # ============================== WINDOWS VISUALS ==============================
    @{ Id='visualfx'; Cat='Windows Visuals'; Name='Visual effects: best performance'; Risk='Safe'; Presets='GX'
       Desc='Kills window minimize/maximize and taskbar animations. Desktop feels snappier, frees a little GPU/CPU.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\VisualEffects' 'VisualFXSetting' 2),
             (RegV "$DESK\WindowMetrics" 'MinAnimate' '0' 'String'), (RegV $ADV 'TaskbarAnimations' 0)) },
    @{ Id='transparency'; Cat='Windows Visuals'; Name='Disable transparency effects'; Risk='Safe'; Presets='GX'
       Desc='Removes the blur/acrylic effect on taskbar and Start. Saves GPU work, especially on integrated graphics.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize' 'EnableTransparency' 0)) },
    @{ Id='menudelay'; Cat='Windows Visuals'; Name='Instant menus + no startup-app delay'; Risk='Safe'; Presets='SGX'
       Desc='Menus open instantly and startup apps launch without the artificial 10 s delay.'
       Reg=@((RegV $DESK 'MenuShowDelay' '0' 'String'), (RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Serialize' 'StartupDelayInMSec' 0)) },
    @{ Id='aeropeek'; Cat='Windows Visuals'; Name='Disable Aero Peek'; Risk='Safe'; Presets='GX'
       Desc='No more desktop preview when the mouse touches the bottom-right corner.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\DWM' 'EnableAeroPeek' 0)) },
    @{ Id='smoothscroll'; Cat='Windows Visuals'; Name='Disable smooth-scrolling animation'; Risk='Safe'; Presets='X'
       Desc='List boxes jump instantly instead of animating.'
       Reg=@((RegV $DESK 'SmoothScroll' 0)) },
    @{ Id='listviewfx'; Cat='Windows Visuals'; Name='Disable icon shadows + translucent selection'; Risk='Safe'; Presets='GX'
       Desc='Removes desktop icon label drop shadows and the alpha-blended selection rectangle.'
       Reg=@((RegV $ADV 'ListviewAlphaSelect' 0), (RegV $ADV 'ListviewShadow' 0)) },
    @{ Id='dragfull'; Cat='Windows Visuals'; Name='Show outline while dragging windows'; Risk='Safe'; Presets='X'
       Desc='Windows are not redrawn continuously while you move them.'
       Reg=@((RegV $DESK 'DragFullWindows' '0' 'String')) },
    @{ Id='logonblur'; Cat='Windows Visuals'; Name='Disable sign-in screen blur'; Risk='Safe'; Presets='GX'
       Desc='The lock/sign-in background is shown sharp instead of with the acrylic blur.'
       Reg=@((RegV "$PW\System" 'DisableAcrylicBackgroundOnLogon' 1)) },
    @{ Id='lockscreen'; Cat='Windows Visuals'; Name='Skip the lock screen'; Risk='Safe'; Presets='X'
       Desc='Go straight to the password/PIN box (Pro/Enterprise editions honour this policy).'
       Reg=@((RegV "$PW\Personalization" 'NoLockScreen' 1)) },
    @{ Id='startupsound'; Cat='Windows Visuals'; Name='Disable Windows startup sound'; Risk='Safe'; Presets='X'
       Desc='Silences the sound played at sign-in.'
       Reg=@((RegV 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\LogonUI\BootAnimation' 'DisableStartupSound' 1)) },
    @{ Id='darkmode'; Cat='Windows Visuals'; Name='Dark mode everywhere'; Risk='Safe'; Presets=''
       Desc='Dark theme for Windows and apps. Because it looks cool.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize' 'AppsUseLightTheme' 0),
             (RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize' 'SystemUsesLightTheme' 0)) },
    @{ Id='wallpaperq'; Cat='Windows Visuals'; Name='Full-quality wallpaper'; Risk='Safe'; Presets=''
       Desc='Windows stops re-compressing your wallpaper to 85% JPEG quality.'
       Reg=@((RegV $DESK 'JPEGImportQuality' 100)) },

    # ============================== EXPLORER & TASKBAR ==============================
    @{ Id='fileext'; Cat='Explorer & Taskbar'; Name='Show file extensions'; Risk='Safe'; Presets='SGX'
       Desc='See "virus.pdf.exe" for what it really is. Security win as much as convenience.'
       Reg=@((RegV $ADV 'HideFileExt' 0)) },
    @{ Id='hiddenfiles'; Cat='Explorer & Taskbar'; Name='Show hidden files'; Risk='Safe'; Presets=''
       Desc='Shows hidden files and folders like AppData (where .minecraft lives).'
       Reg=@((RegV $ADV 'Hidden' 1)) },
    @{ Id='thispc'; Cat='Explorer & Taskbar'; Name='Explorer opens to This PC'; Risk='Safe'; Presets=''
       Desc='File Explorer opens on your drives instead of Home/Quick access.'
       Reg=@((RegV $ADV 'LaunchTo' 1)) },
    @{ Id='quickaccess'; Cat='Explorer & Taskbar'; Name='Hide recent/frequent files in Explorer'; Risk='Safe'; Presets=''
       Desc='Explorer Home stops listing recently opened files and folders (faster, more private).'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer' 'ShowRecent' 0),
             (RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer' 'ShowFrequent' 0)) },
    @{ Id='classicmenu'; Cat='Explorer & Taskbar'; Name='Classic right-click menu (Windows 11)'; Risk='Safe'; Presets=''; Applies={ $script:IsWin11 }
       Desc='Brings back the full right-click menu without "Show more options". Restart Explorer to see it.'
       Apply={ $k = 'HKCU:\Software\Classes\CLSID\{86ca1aa0-34aa-4e8b-a509-50c905bae2a2}\InprocServer32'; New-Item -Path $k -Force | Out-Null; Set-ItemProperty -Path $k -Name '(default)' -Value '' }
       Revert={ Remove-Item -Path 'HKCU:\Software\Classes\CLSID\{86ca1aa0-34aa-4e8b-a509-50c905bae2a2}' -Recurse -Force -ErrorAction SilentlyContinue }
       Check={ Test-Path 'HKCU:\Software\Classes\CLSID\{86ca1aa0-34aa-4e8b-a509-50c905bae2a2}\InprocServer32' } },
    @{ Id='widgets'; Cat='Explorer & Taskbar'; Name='Disable Widgets board'; Risk='Safe'; Presets='SGX'; Applies={ $script:IsWin11 }
       Desc='Removes Widgets and its background Edge WebView processes (saves 100-300 MB RAM).'
       Reg=@((RegV 'HKLM:\SOFTWARE\Policies\Microsoft\Dsh' 'AllowNewsAndInterests' 0)) },
    @{ Id='newsinterests'; Cat='Explorer & Taskbar'; Name='Disable News and Interests (Windows 10)'; Risk='Safe'; Presets='SGX'; Applies={ -not $script:IsWin11 }
       Desc='Removes the weather/news taskbar widget and its background process.'
       Reg=@((RegV "$PW\Windows Feeds" 'EnableFeeds' 0)) },
    @{ Id='taskview'; Cat='Explorer & Taskbar'; Name='Hide Task View button'; Risk='Safe'; Presets=''
       Desc='Removes the Task View button from the taskbar (Win+Tab still works).'
       Reg=@((RegV $ADV 'ShowTaskViewButton' 0)) },
    @{ Id='chaticon'; Cat='Explorer & Taskbar'; Name='Remove Teams Chat icon'; Risk='Safe'; Presets='SGX'; Applies={ $script:IsWin11 }
       Desc='Removes the consumer Teams "Chat" button from the taskbar.'
       Reg=@((RegV "$PW\Windows Chat" 'ChatIcon' 3)) },
    @{ Id='searchbox'; Cat='Explorer & Taskbar'; Name='Taskbar search: icon only'; Risk='Safe'; Presets=''
       Desc='Shrinks the big taskbar search box to an icon.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Search' 'SearchboxTaskbarMode' 1)) },
    @{ Id='copilot'; Cat='Explorer & Taskbar'; Name='Disable Copilot'; Risk='Safe'; Presets='SGX'; Applies={ $script:IsWin11 }
       Desc='Removes the Copilot button and sidebar.'
       Reg=@((RegV 'HKCU:\Software\Policies\Microsoft\Windows\WindowsCopilot' 'TurnOffWindowsCopilot' 1), (RegV $ADV 'ShowCopilotButton' 0)) },
    @{ Id='recall'; Cat='Explorer & Taskbar'; Name='Disable Recall snapshots'; Risk='Safe'; Presets='SGX'; Applies={ $script:IsWin11 }
       Desc='Stops Windows taking screenshots of everything you do (Copilot+ PCs). Saves disk, CPU and privacy.'
       Reg=@((RegV 'HKCU:\Software\Policies\Microsoft\Windows\WindowsAI' 'DisableAIDataAnalysis' 1)) },
    @{ Id='taskbarleft'; Cat='Explorer & Taskbar'; Name='Left-aligned taskbar'; Risk='Safe'; Presets=''; Applies={ $script:IsWin11 }
       Desc='Start button back in the corner, Windows 10 style.'
       Reg=@((RegV $ADV 'TaskbarAl' 0)) },
    @{ Id='endtask'; Cat='Explorer & Taskbar'; Name='"End task" on taskbar right-click'; Risk='Safe'; Presets='GX'; Applies={ $script:IsWin11 }
       Desc='Right-click a frozen game on the taskbar and kill it instantly - no Task Manager needed.'
       Reg=@((RegV "$ADV\TaskbarDeveloperSettings" 'TaskbarEndTask' 1)) },
    @{ Id='fullpath'; Cat='Explorer & Taskbar'; Name='Show full path in Explorer title'; Risk='Safe'; Presets=''
       Desc='Shows the complete folder path in the Explorer title bar.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\CabinetState' 'FullPath' 1)) },
    @{ Id='shortcuttext'; Cat='Explorer & Taskbar'; Name='No "- Shortcut" on new shortcuts'; Risk='Safe'; Presets=''
       Desc='New shortcuts are named "Minecraft" instead of "Minecraft - Shortcut".'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer' 'link' ([byte[]](0, 0, 0, 0)) 'Binary')) },
    @{ Id='aeroshake'; Cat='Explorer & Taskbar'; Name='Disable Aero Shake'; Risk='Safe'; Presets='GX'
       Desc='Shaking a window no longer minimizes every other window.'
       Reg=@((RegV $ADV 'DisallowShaking' 1)) },
    @{ Id='iconcache'; Cat='Explorer & Taskbar'; Name='Bigger icon cache'; Risk='Safe'; Presets='GX'
       Desc='Raises the icon cache to 4096 entries so Explorer and the desktop redraw icons faster.'
       Reg=@((RegV 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Explorer' 'Max Cached Icons' '4096' 'String')) },

    # ============================== ADS & NOTIFICATIONS ==============================
    @{ Id='ads'; Cat='Ads & Notifications'; Name='Disable Start ads, tips and suggested apps'; Risk='Safe'; Presets='SGX'
       Desc='No more Start menu ads, "tips", or silently installed suggested apps (Candy Crush & co).'
       Reg=@((RegV $CDM 'SilentInstalledAppsEnabled' 0), (RegV $CDM 'SystemPaneSuggestionsEnabled' 0), (RegV $CDM 'SubscribedContent-338388Enabled' 0),
             (RegV $CDM 'SubscribedContent-338389Enabled' 0), (RegV $CDM 'SoftLandingEnabled' 0), (RegV $CDM 'PreInstalledAppsEnabled' 0),
             (RegV $CDM 'OemPreInstalledAppsEnabled' 0)) },
    @{ Id='lockscreentips'; Cat='Ads & Notifications'; Name='Disable lock-screen fun facts and tips'; Risk='Safe'; Presets='SGX'
       Desc='Removes the "fun facts, tips and tricks" text on the lock screen.'
       Reg=@((RegV $CDM 'RotatingLockScreenOverlayEnabled' 0), (RegV $CDM 'SubscribedContent-338387Enabled' 0)) },
    @{ Id='spotlight'; Cat='Ads & Notifications'; Name='Disable Windows Spotlight'; Risk='Safe'; Presets='X'
       Desc='Stops downloading rotating lock-screen images and the "Learn about this picture" desktop icon.'
       Reg=@((RegV $CDM 'RotatingLockScreenEnabled' 0), (RegV 'HKCU:\Software\Policies\Microsoft\Windows\CloudContent' 'DisableWindowsSpotlightFeatures' 1)) },
    @{ Id='settingsads'; Cat='Ads & Notifications'; Name='Disable suggestions in Settings'; Risk='Safe'; Presets='SGX'
       Desc='Removes promoted content and "suggested" cards inside the Settings app.'
       Reg=@((RegV $CDM 'SubscribedContent-338393Enabled' 0), (RegV $CDM 'SubscribedContent-353694Enabled' 0), (RegV $CDM 'SubscribedContent-353696Enabled' 0)) },
    @{ Id='welcome'; Cat='Ads & Notifications'; Name='Disable "Windows welcome experience"'; Risk='Safe'; Presets='SGX'
       Desc='No more full-screen "what''s new" pages after updates.'
       Reg=@((RegV $CDM 'SubscribedContent-310093Enabled' 0)) },
    @{ Id='scoobe'; Cat='Ads & Notifications'; Name='Disable "Finish setting up your device" nag'; Risk='Safe'; Presets='SGX'
       Desc='Stops the full-screen nag pushing Microsoft 365, OneDrive and Game Pass after updates.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\UserProfileEngagement' 'ScoobeSystemSettingEnabled' 0)) },
    @{ Id='startrecs'; Cat='Ads & Notifications'; Name='Disable Start menu promotions'; Risk='Safe'; Presets='SGX'
       Desc='Removes promoted tips/apps from the Start "Recommended" section.'
       Reg=@((RegV $ADV 'Start_IrisRecommendations' 0)) },
    @{ Id='syncads'; Cat='Ads & Notifications'; Name='Disable OneDrive ads in Explorer'; Risk='Safe'; Presets='SGX'
       Desc='Removes sync-provider "notifications" (OneDrive/M365 ads) inside File Explorer.'
       Reg=@((RegV $ADV 'ShowSyncProviderNotifications' 0)) },
    @{ Id='consumer'; Cat='Ads & Notifications'; Name='Disable Microsoft consumer experiences'; Risk='Safe'; Presets='SGX'
       Desc='Policy that blocks auto-installed third-party apps and promotional tiles.'
       Reg=@((RegV "$PW\CloudContent" 'DisableWindowsConsumerFeatures' 1), (RegV "$PW\CloudContent" 'DisableSoftLanding' 1)) },
    @{ Id='accountnotif'; Cat='Ads & Notifications'; Name='Disable Microsoft account nags in Start'; Risk='Safe'; Presets='SGX'
       Desc='Removes "Back up your PC / sign in to Microsoft" badges on the Start account menu.'
       Reg=@((RegV $ADV 'Start_AccountNotifications' 0)) },
    @{ Id='feedbackfreq'; Cat='Ads & Notifications'; Name='Never ask for feedback'; Risk='Safe'; Presets='SGX'
       Desc='Windows stops popping up "How likely are you to recommend Windows?" surveys.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Siuf\Rules' 'NumberOfSIUFInPeriod' 0), (RegV "$PW\DataCollection" 'DoNotShowFeedbackNotifications' 1)) },
    @{ Id='bingsearch'; Cat='Ads & Notifications'; Name='Disable Bing web results in Start search'; Risk='Safe'; Presets='SGX'
       Desc='Start search only searches your PC - noticeably faster and no web junk.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Search' 'BingSearchEnabled' 0),
             (RegV 'HKCU:\Software\Policies\Microsoft\Windows\Explorer' 'DisableSearchBoxSuggestions' 1)) },
    @{ Id='searchhl'; Cat='Ads & Notifications'; Name='Disable search highlights'; Risk='Safe'; Presets='SGX'
       Desc='Removes the daily doodles/trending content in the search box and panel.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\SearchSettings' 'IsDynamicSearchBoxEnabled' 0)) },
    @{ Id='cortana'; Cat='Ads & Notifications'; Name='Disable Cortana'; Risk='Safe'; Presets='SGX'
       Desc='Turns Cortana off via policy.'
       Reg=@((RegV "$PW\Windows Search" 'AllowCortana' 0)) },
    @{ Id='toasts'; Cat='Ads & Notifications'; Name='Disable all Windows toast notifications'; Risk='Moderate'; Presets='X'
       Desc='Silences every Windows pop-up notification (apps can still show their own in-app alerts).'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\PushNotifications' 'ToastEnabled' 0)) },

    # ============================== PRIVACY & TELEMETRY ==============================
    @{ Id='telemetry'; Cat='Privacy & Telemetry'; Name='Disable telemetry + tracking services'; Risk='Safe'; Presets='SGX'
       Desc='Minimum diagnostic data, and turns off DiagTrack (Connected User Experiences) and the WAP push service.'
       Reg=@((RegV "$PW\DataCollection" 'AllowTelemetry' 0)); Svc=@('DiagTrack', 'dmwappushservice') },
    @{ Id='adid'; Cat='Privacy & Telemetry'; Name='Disable advertising ID'; Risk='Safe'; Presets='SGX'
       Desc='Apps can no longer use a per-user ID to profile you for ads.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\AdvertisingInfo' 'Enabled' 0), (RegV "$PW\AdvertisingInfo" 'DisabledByGroupPolicy' 1)) },
    @{ Id='activity'; Cat='Privacy & Telemetry'; Name='Disable activity history'; Risk='Safe'; Presets='SGX'
       Desc='Windows stops logging which apps/files you use and uploading that timeline.'
       Reg=@((RegV "$PW\System" 'EnableActivityFeed' 0), (RegV "$PW\System" 'PublishUserActivities' 0), (RegV "$PW\System" 'UploadUserActivities' 0)) },
    @{ Id='location'; Cat='Privacy & Telemetry'; Name='Disable location access'; Risk='Moderate'; Presets='X'
       Desc='Blocks apps and Windows from using your location (weather/maps will ask you for a city).'
       Reg=@((RegV "$CS\location" 'Value' 'Deny' 'String')) },
    @{ Id='appdiag'; Cat='Privacy & Telemetry'; Name='Block apps reading diagnostic info'; Risk='Safe'; Presets='SGX'
       Desc='Store apps can no longer read diagnostic data about other running apps.'
       Reg=@((RegV "$CS\appDiagnostics" 'Value' 'Deny' 'String')) },
    @{ Id='tailored'; Cat='Privacy & Telemetry'; Name='Disable tailored experiences'; Risk='Safe'; Presets='SGX'
       Desc='Microsoft stops using your diagnostic data for personalised tips and ads.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Privacy' 'TailoredExperiencesWithDiagnosticDataEnabled' 0)) },
    @{ Id='inking'; Cat='Privacy & Telemetry'; Name='Disable typing & inking personalisation'; Risk='Safe'; Presets='SGX'
       Desc='Stops collecting what you type and write to build a personal dictionary in the cloud.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\InputPersonalization' 'RestrictImplicitInkCollection' 1),
             (RegV 'HKCU:\Software\Microsoft\InputPersonalization' 'RestrictImplicitTextCollection' 1),
             (RegV 'HKCU:\Software\Microsoft\InputPersonalization\TrainedDataStore' 'HarvestContacts' 0),
             (RegV 'HKCU:\Software\Microsoft\Personalization\Settings' 'AcceptedPrivacyPolicy' 0)) },
    @{ Id='speech'; Cat='Privacy & Telemetry'; Name='Disable online speech recognition'; Risk='Safe'; Presets='SGX'
       Desc='Voice input is no longer sent to Microsoft servers.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Speech_OneCore\Settings\OnlineSpeechPrivacy' 'HasAccepted' 0)) },
    @{ Id='apptrack'; Cat='Privacy & Telemetry'; Name='Disable app-launch tracking'; Risk='Safe'; Presets='SGX'
       Desc='Windows stops tracking which apps you launch (removes "Most used" in Start).'
       Reg=@((RegV $ADV 'Start_TrackProgs' 0)) },
    @{ Id='doctrack'; Cat='Privacy & Telemetry'; Name='Disable recent documents tracking'; Risk='Safe'; Presets=''
       Desc='No recent files in Start, Jump Lists and Explorer.'
       Reg=@((RegV $ADV 'Start_TrackDocs' 0)) },
    @{ Id='langlist'; Cat='Privacy & Telemetry'; Name='Hide language list from websites'; Risk='Safe'; Presets='SGX'
       Desc='Websites can no longer read your installed language list for fingerprinting.'
       Reg=@((RegV 'HKCU:\Control Panel\International\User Profile' 'HttpAcceptLanguageOptOut' 1)) },
    @{ Id='wer'; Cat='Privacy & Telemetry'; Name='Disable Windows Error Reporting'; Risk='Safe'; Presets='GX'
       Desc='Crashes are no longer packaged and uploaded to Microsoft (no more "checking for a solution" hang after a game crash).'
       Reg=@((RegV 'HKLM:\SOFTWARE\Microsoft\Windows\Windows Error Reporting' 'Disabled' 1)) },
    @{ Id='ceip'; Cat='Privacy & Telemetry'; Name='Disable Customer Experience Program'; Risk='Safe'; Presets='SGX'
       Desc='Opts out of the CEIP / SQM usage data program.'
       Reg=@((RegV 'HKLM:\SOFTWARE\Policies\Microsoft\SQMClient\Windows' 'CEIPEnable' 0)) },
    @{ Id='appcompat'; Cat='Privacy & Telemetry'; Name='Disable app-compatibility telemetry'; Risk='Safe'; Presets='SGX'
       Desc='Stops the inventory collector and application telemetry engine.'
       Reg=@((RegV "$PW\AppCompat" 'AITEnable' 0), (RegV "$PW\AppCompat" 'DisableInventory' 1)) },
    @{ Id='clipsync'; Cat='Privacy & Telemetry'; Name='Disable cloud clipboard sync'; Risk='Safe'; Presets='SGX'
       Desc='Clipboard contents are no longer synced across devices through Microsoft.'
       Reg=@((RegV "$PW\System" 'AllowCrossDeviceClipboard' 0)) },
    @{ Id='handwriting'; Cat='Privacy & Telemetry'; Name='Disable handwriting data sharing'; Risk='Safe'; Presets='SGX'
       Desc='Pen handwriting samples are not sent to Microsoft.'
       Reg=@((RegV "$PW\TabletPC" 'PreventHandwritingDataSharing' 1)) },
    @{ Id='devtelemetry'; Cat='Privacy & Telemetry'; Name='Opt out of PowerShell / .NET CLI telemetry'; Risk='Safe'; Presets='SGX'
       Desc='Sets the official opt-out environment variables for PowerShell 7 and the dotnet CLI.'
       Reg=@((RegV 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment' 'POWERSHELL_TELEMETRY_OPTOUT' '1' 'String'),
             (RegV 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment' 'DOTNET_CLI_TELEMETRY_OPTOUT' '1' 'String')) },

    # ============================== SCHEDULED TASKS ==============================
    (TaskT 't_appraiser' 'Disable Compatibility Appraiser' 'Safe' 'SGX' @('\Microsoft\Windows\Application Experience\Microsoft Compatibility Appraiser*') 'The telemetry task infamous for random 100% CPU/disk spikes (CompatTelRunner.exe).'),
    (TaskT 't_pdu' 'Disable ProgramDataUpdater' 'Safe' 'SGX' @('\Microsoft\Windows\Application Experience\ProgramDataUpdater', '\Microsoft\Windows\Application Experience\AitAgent') 'Collects program telemetry for the Customer Experience program.'),
    (TaskT 't_ceip' 'Disable CEIP tasks' 'Safe' 'SGX' @('\Microsoft\Windows\Customer Experience Improvement Program\*') 'Consolidator, USB CEIP and kernel CEIP data collectors.'),
    (TaskT 't_feedback' 'Disable feedback (SIUF) tasks' 'Safe' 'SGX' @('\Microsoft\Windows\Feedback\Siuf\*') 'Background tasks that download and schedule feedback surveys.'),
    (TaskT 't_wer' 'Disable error-report queue task' 'Safe' 'GX' @('\Microsoft\Windows\Windows Error Reporting\QueueReporting') 'Uploads queued crash reports in the background.'),
    (TaskT 't_maps' 'Disable Maps update/toast tasks' 'Safe' 'SGX' @('\Microsoft\Windows\Maps\*') 'Offline map updates and map notifications.'),
    (TaskT 't_diskdiag' 'Disable disk diagnostic data collector' 'Safe' 'SGX' @('\Microsoft\Windows\DiskDiagnostic\Microsoft-Windows-DiskDiagnosticDataCollector') 'Sends disk diagnostic data to Microsoft.'),
    (TaskT 't_autochk' 'Disable Autochk proxy task' 'Safe' 'SGX' @('\Microsoft\Windows\Autochk\Proxy') 'Collects and uploads SQM data on boot.'),
    (TaskT 't_cloudexp' 'Disable CloudExperienceHost task' 'Safe' 'GX' @('\Microsoft\Windows\CloudExperienceHost\CreateObjectTask') 'Background task for out-of-box/upsell experiences.'),
    (TaskT 't_devcensus' 'Disable Device Census' 'Safe' 'SGX' @('\Microsoft\Windows\Device Information\Device*') 'Inventories your hardware for telemetry.'),
    (TaskT 't_powerdiag' 'Disable power efficiency diagnostics' 'Safe' 'SGX' @('\Microsoft\Windows\Power Efficiency Diagnostics\AnalyzeSystem') 'Periodic power analysis that can spike the CPU.'),
    (TaskT 't_family' 'Disable Family Safety tasks' 'Safe' 'GX' @('\Microsoft\Windows\Shell\FamilySafety*') 'Only needed if this PC uses Microsoft Family parental controls.'),
    (TaskT 't_office' 'Disable Office telemetry tasks' 'Safe' 'SGX' @('\Microsoft\Office\OfficeTelemetryAgent*') 'Office telemetry agent logon/fallback tasks.'),
    (TaskT 't_nvidia' 'Disable NVIDIA telemetry tasks' 'Safe' 'GX' @('\NvTm*') 'NVIDIA crash/telemetry report tasks. Driver unaffected.'),
    (TaskT 't_xblsave' 'Disable Xbox game-save sync task' 'Moderate' '' @('\Microsoft\XblGameSave\XblGameSaveTask') 'Breaks Xbox/Game Pass cloud saves - only if you do not use them.'),
    (TaskT 't_edgeupd' 'Disable Edge auto-update tasks' 'Moderate' 'X' @('\MicrosoftEdgeUpdateTaskMachine*') 'Edge stops checking for updates in the background (it still updates when opened).'),
    (TaskT 't_googleupd' 'Disable Google auto-update tasks' 'Moderate' 'X' @('\GoogleUpdateTaskMachine*', '\GoogleSystem\GoogleUpdater\*') 'Chrome stops checking for updates in the background (it still updates when opened).'),

    # ============================== SERVICES ==============================
    (SvcT 's_fax' 'Disable Fax service' 'Safe' 'SGX' @('Fax') 'Nobody has faxed since 2003.'),
    (SvcT 's_remotereg' 'Disable Remote Registry' 'Safe' 'SGX' @('RemoteRegistry') 'Blocks remote editing of your registry over the network (security).'),
    (SvcT 's_insider' 'Disable Windows Insider service' 'Safe' 'SGX' @('wisvc') 'Only needed if you are in the Insider preview program.'),
    (SvcT 's_retail' 'Disable Retail Demo service' 'Safe' 'SGX' @('RetailDemo') 'For store display PCs only.'),
    (SvcT 's_maps' 'Disable Downloaded Maps Manager' 'Safe' 'SGX' @('MapsBroker') 'Manages offline maps for the Maps app.'),
    (SvcT 's_wmpnet' 'Disable WMP network sharing' 'Safe' 'SGX' @('WMPNetworkSvc') 'Windows Media Player library sharing over the network.'),
    (SvcT 's_parental' 'Disable Parental Controls service' 'Safe' 'GX' @('WpcMonSvc') 'Only needed for Microsoft Family accounts.'),
    (SvcT 's_smartcard' 'Disable Smart Card services' 'Safe' 'GX' @('SCardSvr', 'ScDeviceEnum', 'SCPolicySvc') 'Only needed for corporate smart-card logins.'),
    (SvcT 's_trkwks' 'Disable Distributed Link Tracking' 'Safe' 'GX' @('TrkWks') 'Tracks moved files across network shares - useless at home.'),
    (SvcT 's_offline' 'Disable Offline Files' 'Safe' 'GX' @('CscService') 'Corporate network-share caching.'),
    (SvcT 's_payments' 'Disable Payments & NFC service' 'Safe' 'GX' @('SEMgrSvc') 'Tap-to-pay NFC on PCs.'),
    (SvcT 's_wallet' 'Disable Wallet service' 'Safe' 'GX' @('WalletService') 'Microsoft Wallet backend.'),
    (SvcT 's_alljoyn' 'Disable AllJoyn Router' 'Safe' 'GX' @('AJRouter') 'Old IoT device protocol.'),
    (SvcT 's_hyperv' 'Disable Hyper-V guest services' 'Safe' 'GX' @('vmickvpexchange', 'vmicguestinterface', 'vmicshutdown', 'vmicheartbeat', 'vmicvmsession', 'vmicrdv', 'vmictimesync', 'vmicvss') 'Only used when Windows itself runs inside a Hyper-V virtual machine.'),
    (SvcT 's_diaghub' 'Disable Diagnostics Hub collector' 'Safe' 'GX' @('diagnosticshub.standardcollector.service') 'Visual Studio / diagnostics data collector.'),
    (SvcT 's_diagsvc' 'Disable Diagnostic Execution service' 'Safe' 'GX' @('diagsvc') 'Runs troubleshooting/diagnostic actions for support.'),
    (SvcT 's_rasauto' 'Disable Remote Access Auto Connection' 'Safe' 'GX' @('RasAuto') 'Auto-dials VPN/dial-up connections.'),
    (SvcT 's_wercpl' 'Disable Problem Reports control panel support' 'Safe' 'GX' @('wercplsupport') 'Backend for the Problem Reports history page.'),
    (SvcT 's_wcn' 'Disable Windows Connect Now' 'Safe' 'GX' @('wcncsvc') 'WPS push-button Wi-Fi setup.'),
    (SvcT 's_perception' 'Disable Mixed Reality services' 'Safe' 'GX' @('spectrum', 'perceptionsimulation', 'MixedRealityOpenXRSvc') 'Windows Mixed Reality headsets only (not SteamVR/Quest Link).'),
    (SvcT 's_geo' 'Disable Geolocation service' 'Moderate' 'X' @('lfsvc') 'Location for Windows/apps. Weather and Find My Device lose location.'),
    (SvcT 's_phone' 'Disable Phone service' 'Moderate' 'X' @('PhoneSvc') 'Telephony state for Phone Link calls.'),
    (SvcT 's_pca' 'Disable Program Compatibility Assistant' 'Moderate' 'X' @('PcaSvc') 'Stops "this program might not have installed correctly" popups and their background monitoring.'),
    (SvcT 's_dps' 'Disable Diagnostic Policy services' 'Moderate' 'X' @('DPS', 'WdiServiceHost', 'WdiSystemHost') 'Problem detection for troubleshooters. Troubleshooters and per-app data usage stop working.'),
    (SvcT 's_rdp' 'Disable Remote Desktop host' 'Moderate' 'X' @('TermService', 'UmRdpService', 'SessionEnv') 'Nobody can RDP into this PC. Remote Desktop from this PC to others still works.'),
    (SvcT 's_hotspot' 'Disable Mobile Hotspot service' 'Moderate' 'X' @('icssvc') 'You can no longer share this PC''s internet as a hotspot.'),
    (SvcT 's_tapi' 'Disable Telephony service' 'Moderate' 'X' @('TapiSrv') 'Legacy modem/telephony API.'),
    (SvcT 's_spooler' 'Disable Print Spooler' 'Moderate' '' @('Spooler') 'Only if you never print. Also closes the PrintNightmare attack surface.'),
    (SvcT 's_bluetooth' 'Disable Bluetooth support' 'Moderate' '' @('bthserv', 'BTAGService') 'Only if you use no Bluetooth headphones, controllers or mice.'),
    (SvcT 's_biometric' 'Disable Biometric service' 'Moderate' '' @('WbioSrvc') 'Breaks Windows Hello fingerprint/face sign-in.'),
    (SvcT 's_wia' 'Disable scanner service (WIA)' 'Moderate' '' @('stisvc') 'Breaks scanners. Webcams are not affected.'),
    (SvcT 's_sensors' 'Disable sensor services' 'Moderate' '' @('SensorService', 'SensrSvc', 'SensorDataService') 'Desktops only: laptops lose auto-rotate and auto-brightness.'),
    (SvcT 's_xbl' 'Disable Xbox Live services' 'Moderate' '' @('XblAuthManager', 'XblGameSave', 'XboxNetApiSvc') 'Breaks Xbox app, Game Pass and Minecraft Bedrock sign-in. Only if you never use them.'),
    (SvcT 's_xboxacc' 'Disable Xbox accessory service' 'Moderate' '' @('XboxGipSvc') 'Xbox controller firmware/accessory config. Controllers still work for games.'),
    (SvcT 's_edgeupd' 'Edge updater services to Manual' 'Safe' 'GX' @('edgeupdate', 'edgeupdatem') 'Edge updater stops starting with Windows; it still runs when Edge needs it.' 3),
    (SvcT 's_googleupd' 'Google updater services to Manual' 'Safe' 'GX' @('gupdate', 'gupdatem') 'Google updater stops starting with Windows; Chrome still updates itself.' 3),
    (SvcT 's_adobe' 'Adobe updater service to Manual' 'Safe' 'GX' @('AdobeARMservice') 'Adobe Acrobat updater stops starting with Windows.' 3),

    # ============================== APPS & BROWSERS ==============================
    @{ Id='bgapps'; Cat='Apps & Browsers'; Name='Block background Store apps'; Risk='Safe'; Presets='SGX'
       Desc='Stops Microsoft Store apps running in the background when you are not using them.'
       Reg=@((RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\BackgroundAccessApplications' 'GlobalUserDisabled' 1),
             (RegV 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Search' 'BackgroundAppGlobalToggle' 0)) },
    @{ Id='edgeboost'; Cat='Apps & Browsers'; Name='Stop Edge running in the background'; Risk='Safe'; Presets='SGX'
       Desc='Disables Edge Startup Boost and background mode - saves RAM when Edge is closed. Edge will show "managed by your organization".'
       Reg=@((RegV $EDGE 'StartupBoostEnabled' 0), (RegV $EDGE 'BackgroundModeEnabled' 0)) },
    @{ Id='edgetel'; Cat='Apps & Browsers'; Name='Disable Edge diagnostic data'; Risk='Safe'; Presets='SGX'
       Desc='Edge sends only required diagnostic data.'
       Reg=@((RegV $EDGE 'DiagnosticData' 0), (RegV $EDGE 'PersonalizationReportingEnabled' 0)) },
    @{ Id='edgebloat'; Cat='Apps & Browsers'; Name='Remove Edge shopping, sidebar and recommendations'; Risk='Safe'; Presets='GX'
       Desc='Disables the shopping assistant, Copilot sidebar and "recommended" popups in Edge.'
       Reg=@((RegV $EDGE 'EdgeShoppingAssistantEnabled' 0), (RegV $EDGE 'HubsSidebarEnabled' 0), (RegV $EDGE 'ShowRecommendationsEnabled' 0)) },
    @{ Id='chromebg'; Cat='Apps & Browsers'; Name='Stop Chrome running in the background'; Risk='Safe'; Presets='GX'
       Desc='Chrome fully exits when you close it. Chrome will show "managed by your organization".'
       Applies={ (Test-Path "$env:ProgramFiles\Google\Chrome") -or (Test-Path "${env:ProgramFiles(x86)}\Google\Chrome") -or (Test-Path "$env:LOCALAPPDATA\Google\Chrome\Application") }
       Reg=@((RegV 'HKLM:\SOFTWARE\Policies\Google\Chrome' 'BackgroundModeEnabled' 0)) },
    @{ Id='onedrivestart'; Cat='Apps & Browsers'; Name='Stop OneDrive starting with Windows'; Risk='Safe'; Presets='GX'
       Desc='Same as disabling it in Task Manager > Startup. OneDrive still works when you open it.'
       Applies={ $null -ne (Get-RegValue 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' 'OneDrive') }
       Apply={ Set-Reg 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run' 'OneDrive' ([byte[]](3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)) 'Binary' }
       Revert={ Restore-Reg 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run' 'OneDrive' }
       Check={ $v = Get-RegValue 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run' 'OneDrive'; $v -and ($v[0] % 2 -eq 1) } },

    # ============================== WINDOWS UPDATE ==============================
    @{ Id='norestart'; Cat='Windows Update'; Name='No auto-restart while signed in'; Risk='Safe'; Presets='SGX'
       Desc='Windows Update will never reboot your PC in the middle of a game or download.'
       Reg=@((RegV "$PW\WindowsUpdate\AU" 'NoAutoRebootWithLoggedOnUsers' 1)) },
    @{ Id='nodrivers'; Cat='Windows Update'; Name='Stop Windows Update replacing drivers'; Risk='Moderate'; Presets='GX'
       Desc='Windows Update stops overwriting your NVIDIA/AMD driver with an old one. You update drivers yourself.'
       Reg=@((RegV "$PW\WindowsUpdate" 'ExcludeWUDriversInQualityUpdate' 1)) },
    @{ Id='devmetadata'; Cat='Windows Update'; Name='Block device metadata downloads'; Risk='Safe'; Presets='GX'
       Desc='Stops Windows downloading OEM device icons/info and bundled manufacturer apps for new hardware.'
       Reg=@((RegV "$PW\Device Metadata" 'PreventDeviceMetadataFromNetwork' 1)) },
    @{ Id='continuous'; Cat='Windows Update'; Name='No early "latest updates as soon as available"'; Risk='Safe'; Presets='GX'
       Desc='Opts out of getting optional feature drops early - fewer surprise changes and bugs.'
       Reg=@((RegV 'HKLM:\SOFTWARE\Microsoft\WindowsUpdate\UX\Settings' 'IsContinuousInnovationOptedIn' 0)) },
    @{ Id='storeauto'; Cat='Windows Update'; Name='Disable automatic Store app updates'; Risk='Moderate'; Presets='X'
       Desc='Store apps no longer update in the background while you play. Update them manually in the Store.'
       Reg=@((RegV 'HKLM:\SOFTWARE\Policies\Microsoft\WindowsStore' 'AutoDownload' 2)) },
    @{ Id='mapsupdate'; Cat='Windows Update'; Name='Disable automatic map updates'; Risk='Safe'; Presets='SGX'
       Desc='Stops background downloads of offline map data.'
       Reg=@((RegV 'HKLM:\SYSTEM\Maps' 'AutoUpdateEnabled' 0)) }
)
#endregion

#region ---------- XAML ----------
[xml]$xaml = @'
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="WaveOptimizer" Width="1180" Height="760" MinWidth="980" MinHeight="620"
        WindowStartupLocation="CenterScreen" Background="Transparent" FontFamily="Segoe UI"
        WindowStyle="None" AllowsTransparency="True" ResizeMode="CanResizeWithGrip">
  <Window.Resources>
    <LinearGradientBrush x:Key="WaveGrad" StartPoint="0,0" EndPoint="1,0">
      <GradientStop Color="#00E5FF" Offset="0"/>
      <GradientStop Color="#3D7BFF" Offset="0.5"/>
      <GradientStop Color="#B44DFF" Offset="1"/>
    </LinearGradientBrush>
    <LinearGradientBrush x:Key="LogoGrad" StartPoint="0,0" EndPoint="1,1">
      <GradientStop Color="#00E5FF" Offset="0"/>
      <GradientStop Color="#3D7BFF" Offset="0.5"/>
      <GradientStop Color="#B44DFF" Offset="1"/>
    </LinearGradientBrush>
    <DrawingImage x:Key="Logo">
      <DrawingImage.Drawing>
        <DrawingGroup>
          <GeometryDrawing Brush="{StaticResource LogoGrad}">
            <GeometryDrawing.Geometry><RectangleGeometry Rect="0,0,64,64" RadiusX="15" RadiusY="15"/></GeometryDrawing.Geometry>
          </GeometryDrawing>
          <GeometryDrawing Geometry="M10,45 C18,45 20,33 28,33 C36,33 38,45 46,45 C50,45 52,41 54,38">
            <GeometryDrawing.Pen><Pen Brush="#70FFFFFF" Thickness="4.5" StartLineCap="Round" EndLineCap="Round"/></GeometryDrawing.Pen>
          </GeometryDrawing>
          <GeometryDrawing Geometry="M10,32 C18,32 20,16 28,16 C36,16 38,32 46,32 C50,32 52,27 54,23">
            <GeometryDrawing.Pen><Pen Brush="White" Thickness="6" StartLineCap="Round" EndLineCap="Round"/></GeometryDrawing.Pen>
          </GeometryDrawing>
        </DrawingGroup>
      </DrawingImage.Drawing>
    </DrawingImage>
    <SolidColorBrush x:Key="Card" Color="#0F1626"/>
    <SolidColorBrush x:Key="CardBorder" Color="#1C2740"/>
    <SolidColorBrush x:Key="Muted" Color="#7F8BA8"/>

    <Style x:Key="Switch" TargetType="ToggleButton">
      <Setter Property="Width" Value="48"/>
      <Setter Property="Height" Value="26"/>
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="ToggleButton">
            <Grid>
              <Border x:Name="Track" CornerRadius="13" Background="#18213A" BorderBrush="#2A3756" BorderThickness="1"/>
              <Ellipse x:Name="Knob" Width="18" Height="18" Fill="#6C7894" HorizontalAlignment="Left" Margin="4,0,0,0"/>
            </Grid>
            <ControlTemplate.Triggers>
              <Trigger Property="IsChecked" Value="True">
                <Setter TargetName="Track" Property="Background" Value="{StaticResource WaveGrad}"/>
                <Setter TargetName="Track" Property="BorderThickness" Value="0"/>
                <Setter TargetName="Knob" Property="HorizontalAlignment" Value="Right"/>
                <Setter TargetName="Knob" Property="Margin" Value="0,0,4,0"/>
                <Setter TargetName="Knob" Property="Fill" Value="White"/>
              </Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>

    <Style x:Key="NavButton" TargetType="RadioButton">
      <Setter Property="Foreground" Value="#9AA6C4"/>
      <Setter Property="FontSize" Value="14"/>
      <Setter Property="Height" Value="44"/>
      <Setter Property="Margin" Value="10,3"/>
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="RadioButton">
            <Border x:Name="Bg" CornerRadius="10" Background="Transparent">
              <Grid>
                <Border x:Name="Bar" Width="4" CornerRadius="2" HorizontalAlignment="Left" Margin="0,10" Background="{StaticResource WaveGrad}" Visibility="Collapsed"/>
                <ContentPresenter VerticalAlignment="Center" Margin="18,0,0,0"/>
              </Grid>
            </Border>
            <ControlTemplate.Triggers>
              <Trigger Property="IsMouseOver" Value="True">
                <Setter TargetName="Bg" Property="Background" Value="#111A2E"/>
              </Trigger>
              <Trigger Property="IsChecked" Value="True">
                <Setter TargetName="Bg" Property="Background" Value="#14203A"/>
                <Setter TargetName="Bar" Property="Visibility" Value="Visible"/>
                <Setter Property="Foreground" Value="White"/>
              </Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>

    <Style x:Key="Btn" TargetType="Button">
      <Setter Property="Foreground" Value="White"/>
      <Setter Property="FontSize" Value="13"/>
      <Setter Property="FontWeight" Value="SemiBold"/>
      <Setter Property="Padding" Value="16,9"/>
      <Setter Property="Margin" Value="0,0,8,0"/>
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Background" Value="#16213A"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="Button">
            <Border x:Name="B" CornerRadius="9" Background="{TemplateBinding Background}" BorderBrush="#2A3A60" BorderThickness="1" Padding="{TemplateBinding Padding}">
              <ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center"/>
            </Border>
            <ControlTemplate.Triggers>
              <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="B" Property="BorderBrush" Value="#00E5FF"/></Trigger>
              <Trigger Property="IsPressed" Value="True"><Setter TargetName="B" Property="Opacity" Value="0.75"/></Trigger>
              <Trigger Property="IsEnabled" Value="False"><Setter TargetName="B" Property="Opacity" Value="0.4"/></Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style x:Key="BtnPrimary" TargetType="Button" BasedOn="{StaticResource Btn}">
      <Setter Property="Background" Value="{StaticResource WaveGrad}"/>
    </Style>
    <Style x:Key="WinBtn" TargetType="Button">
      <Setter Property="Width" Value="40"/><Setter Property="Height" Value="30"/>
      <Setter Property="Foreground" Value="#9AA6C4"/><Setter Property="FontSize" Value="14"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="Button">
            <Border x:Name="B" Background="Transparent" CornerRadius="6"><ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center"/></Border>
            <ControlTemplate.Triggers><Trigger Property="IsMouseOver" Value="True"><Setter TargetName="B" Property="Background" Value="#1C2740"/></Trigger></ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style TargetType="TextBlock"><Setter Property="Foreground" Value="#DCE3F5"/></Style>
    <Style TargetType="CheckBox"><Setter Property="Foreground" Value="#DCE3F5"/></Style>
  </Window.Resources>

  <Border BorderBrush="#1C2740" BorderThickness="1" CornerRadius="14" Background="#070B14" ClipToBounds="True">
    <Grid>
      <Grid.RowDefinitions>
        <RowDefinition Height="110"/>
        <RowDefinition Height="*"/>
        <RowDefinition Height="30"/>
      </Grid.RowDefinitions>
      <Grid.ColumnDefinitions>
        <ColumnDefinition Width="220"/>
        <ColumnDefinition Width="*"/>
      </Grid.ColumnDefinitions>

      <!-- ===== animated wave header ===== -->
      <Grid x:Name="Header" Grid.ColumnSpan="2" Background="#0A1020" ClipToBounds="True">
        <Canvas ClipToBounds="True">
          <Path x:Name="Wave1" Opacity="0.55" Canvas.Top="38">
            <Path.Fill>
              <LinearGradientBrush StartPoint="0,0" EndPoint="1,0">
                <GradientStop Color="#3300E5FF" Offset="0"/><GradientStop Color="#663D7BFF" Offset="0.5"/><GradientStop Color="#33B44DFF" Offset="1"/>
              </LinearGradientBrush>
            </Path.Fill>
            <Path.RenderTransform><TranslateTransform x:Name="Wave1T"/></Path.RenderTransform>
            <Path.Triggers>
              <EventTrigger RoutedEvent="FrameworkElement.Loaded">
                <BeginStoryboard><Storyboard>
                  <DoubleAnimation Storyboard.TargetName="Wave1T" Storyboard.TargetProperty="X" From="0" To="-400" Duration="0:0:6" RepeatBehavior="Forever"/>
                </Storyboard></BeginStoryboard>
              </EventTrigger>
            </Path.Triggers>
          </Path>
          <Path x:Name="Wave2" Opacity="0.45" Canvas.Top="52">
            <Path.Fill>
              <LinearGradientBrush StartPoint="0,0" EndPoint="1,0">
                <GradientStop Color="#55B44DFF" Offset="0"/><GradientStop Color="#4400E5FF" Offset="1"/>
              </LinearGradientBrush>
            </Path.Fill>
            <Path.RenderTransform><TranslateTransform x:Name="Wave2T"/></Path.RenderTransform>
            <Path.Triggers>
              <EventTrigger RoutedEvent="FrameworkElement.Loaded">
                <BeginStoryboard><Storyboard>
                  <DoubleAnimation Storyboard.TargetName="Wave2T" Storyboard.TargetProperty="X" From="-400" To="0" Duration="0:0:9" RepeatBehavior="Forever"/>
                </Storyboard></BeginStoryboard>
              </EventTrigger>
            </Path.Triggers>
          </Path>
        </Canvas>
        <StackPanel Orientation="Horizontal" VerticalAlignment="Top" Margin="22,16,0,0">
          <Image Source="{StaticResource Logo}" Width="42" Height="42" Margin="0,0,12,0" VerticalAlignment="Center"/>
          <TextBlock Text="WAVE" FontSize="34" FontWeight="Black" Foreground="{StaticResource WaveGrad}"/>
          <TextBlock Text="OPTIMIZER" FontSize="34" FontWeight="Light" Margin="8,0,0,0" Foreground="White"/>
          <Border Background="#1A00E5FF" BorderBrush="#5500E5FF" BorderThickness="1" CornerRadius="8" Margin="14,10,0,10" Padding="8,2">
            <TextBlock x:Name="VersionText" Text="v1.1" FontSize="12" Foreground="#00E5FF" VerticalAlignment="Center"/>
          </Border>
        </StackPanel>
        <TextBlock x:Name="HeaderSub" VerticalAlignment="Top" Margin="78,64,0,0" FontSize="13" Foreground="#9AA6C4" Text="Scanning hardware..."/>
        <StackPanel Orientation="Horizontal" HorizontalAlignment="Right" VerticalAlignment="Top" Margin="0,8,10,0">
          <Button x:Name="BtnMin" Style="{StaticResource WinBtn}" Content="&#x2014;"/>
          <Button x:Name="BtnMax" Style="{StaticResource WinBtn}" Content="&#x25A2;"/>
          <Button x:Name="BtnClose" Style="{StaticResource WinBtn}" Content="&#x2715;"/>
        </StackPanel>
      </Grid>

      <!-- ===== sidebar ===== -->
      <Border Grid.Row="1" Background="#090E1A" BorderBrush="#141D33" BorderThickness="0,0,1,0">
        <DockPanel>
          <StackPanel DockPanel.Dock="Top" Margin="0,14,0,0">
            <RadioButton x:Name="NavDash"    Style="{StaticResource NavButton}" Content="&#x25C9;   Dashboard" IsChecked="True"/>
            <RadioButton x:Name="NavTweaks"  Style="{StaticResource NavButton}" Content="&#x26A1;   Tweaks"/>
            <RadioButton x:Name="NavClean"   Style="{StaticResource NavButton}" Content="&#x2728;   Cleanup"/>
            <RadioButton x:Name="NavGpu"     Style="{StaticResource NavButton}" Content="&#x25A3;   GPU &amp; Games"/>
            <RadioButton x:Name="NavMc"      Style="{StaticResource NavButton}" Content="&#x25A6;   Minecraft"/>
            <RadioButton x:Name="NavLog"     Style="{StaticResource NavButton}" Content="&#x2630;   Log"/>
          </StackPanel>
          <Border DockPanel.Dock="Bottom" Margin="14" Padding="12" CornerRadius="10" Background="#0F1626" BorderBrush="#1C2740" BorderThickness="1" VerticalAlignment="Bottom">
            <StackPanel>
              <TextBlock Text="OPTIMIZATION" FontSize="10" FontWeight="Bold" Foreground="#7F8BA8"/>
              <TextBlock x:Name="SideCount" Text="0 / 0" FontSize="22" FontWeight="Bold" Margin="0,4,0,6"/>
              <ProgressBar x:Name="SideBar" Height="6" Minimum="0" Maximum="100" Value="0" Foreground="{StaticResource WaveGrad}" Background="#18213A" BorderThickness="0"/>
              <TextBlock Text="tweaks active" FontSize="11" Foreground="#7F8BA8" Margin="0,6,0,0"/>
            </StackPanel>
          </Border>
        </DockPanel>
      </Border>

      <!-- ===== pages ===== -->
      <Grid Grid.Row="1" Grid.Column="1" Margin="22,18,22,8">

        <!-- DASHBOARD -->
        <ScrollViewer x:Name="PageDash" VerticalScrollBarVisibility="Auto">
          <StackPanel>
            <TextBlock Text="YOUR RIG" FontSize="12" FontWeight="Bold" Foreground="#7F8BA8" Margin="2,0,0,10"/>
            <UniformGrid x:Name="SpecGrid" Columns="3"/>
            <TextBlock Text="WHAT IS ACTUALLY LIMITING YOUR FPS" FontSize="12" FontWeight="Bold" Foreground="#7F8BA8" Margin="2,22,0,10"/>
            <StackPanel x:Name="RecList"/>
          </StackPanel>
        </ScrollViewer>

        <!-- TWEAKS -->
        <DockPanel x:Name="PageTweaks" Visibility="Collapsed">
          <Border DockPanel.Dock="Top" Background="#0F1626" BorderBrush="#1C2740" BorderThickness="1" CornerRadius="12" Padding="14,14,14,8" Margin="0,0,0,8">
            <StackPanel>
              <DockPanel>
                <StackPanel DockPanel.Dock="Right" Orientation="Horizontal">
                  <Button x:Name="BtnRevertAll" Style="{StaticResource Btn}" Content="Revert All"/>
                  <Button x:Name="BtnApply" Style="{StaticResource BtnPrimary}" Content="APPLY CHANGES" Margin="0"/>
                </StackPanel>
                <StackPanel Orientation="Horizontal" VerticalAlignment="Center">
                  <TextBlock Text="Preset:" VerticalAlignment="Center" Foreground="#7F8BA8" Margin="0,0,10,0"/>
                  <Button x:Name="PresetSafe"    Style="{StaticResource Btn}" Content="Safe"/>
                  <Button x:Name="PresetGaming"  Style="{StaticResource Btn}" Content="Gaming"/>
                  <Button x:Name="PresetExtreme" Style="{StaticResource Btn}" Content="Extreme"/>
                  <Button x:Name="PresetCurrent" Style="{StaticResource Btn}" Content="Reset view"/>
                </StackPanel>
              </DockPanel>
              <DockPanel Margin="0,12,0,0">
                <CheckBox x:Name="ChkRestore" DockPanel.Dock="Right" Content="Create restore point first" IsChecked="True" VerticalAlignment="Center" Margin="14,0,0,0"/>
                <TextBlock x:Name="TweakCount" DockPanel.Dock="Right" VerticalAlignment="Center" Foreground="#00E5FF" FontSize="12" Margin="14,0,0,0"/>
                <Grid>
                  <TextBox x:Name="TweakSearch" Height="34" Padding="10,0" VerticalContentAlignment="Center" Background="#070B14" Foreground="#DCE3F5" BorderBrush="#2A3756" CaretBrush="#00E5FF" FontSize="13"/>
                  <TextBlock x:Name="SearchHint" Text="Search tweaks... (e.g. mouse, xbox, ping, telemetry)" IsHitTestVisible="False" Margin="13,0,0,0" VerticalAlignment="Center" Foreground="#56627E"/>
                </Grid>
              </DockPanel>
              <WrapPanel x:Name="CatChips" Margin="0,10,0,0"/>
            </StackPanel>
          </Border>
          <ScrollViewer VerticalScrollBarVisibility="Auto">
            <StackPanel x:Name="TweakList"/>
          </ScrollViewer>
        </DockPanel>

        <!-- CLEANUP -->
        <ScrollViewer x:Name="PageClean" Visibility="Collapsed" VerticalScrollBarVisibility="Auto">
          <StackPanel>
            <TextBlock Text="JUNK CLEANUP" FontSize="12" FontWeight="Bold" Foreground="#7F8BA8" Margin="2,0,0,10"/>
            <Border Background="#0F1626" BorderBrush="#1C2740" BorderThickness="1" CornerRadius="12" Padding="18">
              <StackPanel>
                <CheckBox x:Name="CleanTemp"    Content="User + Windows temp files" IsChecked="True" Margin="0,4"/>
                <CheckBox x:Name="CleanShader"  Content="DirectX / NVIDIA / AMD shader caches (rebuilt on next launch - fixes stutter from corrupt caches)" IsChecked="True" Margin="0,4"/>
                <CheckBox x:Name="CleanWU"      Content="Windows Update download cache" IsChecked="True" Margin="0,4"/>
                <CheckBox x:Name="CleanRecycle" Content="Empty Recycle Bin" IsChecked="False" Margin="0,4"/>
                <CheckBox x:Name="CleanDns"     Content="Flush DNS cache" IsChecked="True" Margin="0,4"/>
                <StackPanel Orientation="Horizontal" Margin="0,14,0,0">
                  <Button x:Name="BtnClean" Style="{StaticResource BtnPrimary}" Content="CLEAN NOW"/>
                  <TextBlock x:Name="CleanResult" VerticalAlignment="Center" Foreground="#00E5FF" FontSize="14" Margin="10,0,0,0"/>
                </StackPanel>
              </StackPanel>
            </Border>
            <TextBlock Text="TOOLS" FontSize="12" FontWeight="Bold" Foreground="#7F8BA8" Margin="2,22,0,10"/>
            <Border Background="#0F1626" BorderBrush="#1C2740" BorderThickness="1" CornerRadius="12" Padding="18">
              <WrapPanel>
                <Button x:Name="BtnStartup"   Style="{StaticResource Btn}" Content="Manage startup apps" Margin="0,0,8,8"/>
                <Button x:Name="BtnRestorePt" Style="{StaticResource Btn}" Content="Create restore point" Margin="0,0,8,8"/>
                <Button x:Name="BtnWinsock"   Style="{StaticResource Btn}" Content="Reset network stack (reboot)" Margin="0,0,8,8"/>
                <Button x:Name="BtnSfc"       Style="{StaticResource Btn}" Content="Repair Windows files (SFC)" Margin="0,0,8,8"/>
                <Button x:Name="BtnStorage"   Style="{StaticResource Btn}" Content="Storage Sense" Margin="0,0,8,8"/>
              </WrapPanel>
            </Border>
          </StackPanel>
        </ScrollViewer>

        <!-- GPU & GAMES -->
        <ScrollViewer x:Name="PageGpu" Visibility="Collapsed" VerticalScrollBarVisibility="Auto">
          <StackPanel>
            <TextBlock Text="FORCE GAMES ONTO YOUR FAST GPU" FontSize="12" FontWeight="Bold" Foreground="#7F8BA8" Margin="2,0,0,10"/>
            <Border Background="#0F1626" BorderBrush="#1C2740" BorderThickness="1" CornerRadius="12" Padding="18">
              <StackPanel>
                <TextBlock TextWrapping="Wrap" Foreground="#9AA6C4" Text="On laptops (and some desktops) Windows often runs games on the weak integrated GPU. That alone can cost 3-5x FPS. Add your game's .exe here to pin it to the high-performance GPU."/>
                <StackPanel Orientation="Horizontal" Margin="0,14,0,10">
                  <Button x:Name="BtnAddGame" Style="{StaticResource BtnPrimary}" Content="+ Add game .exe"/>
                  <Button x:Name="BtnGfxSettings" Style="{StaticResource Btn}" Content="Open Windows graphics settings"/>
                </StackPanel>
                <StackPanel x:Name="GpuPrefList"/>
              </StackPanel>
            </Border>
          </StackPanel>
        </ScrollViewer>

        <!-- MINECRAFT -->
        <ScrollViewer x:Name="PageMc" Visibility="Collapsed" VerticalScrollBarVisibility="Auto">
          <StackPanel>
            <TextBlock Text="MINECRAFT JAVA - THE REAL FPS MULTIPLIERS" FontSize="12" FontWeight="Bold" Foreground="#7F8BA8" Margin="2,0,0,10"/>
            <Border Background="#0F1626" BorderBrush="#1C2740" BorderThickness="1" CornerRadius="12" Padding="18" Margin="0,0,0,12">
              <StackPanel>
                <TextBlock Text="1. Pin javaw.exe to your dedicated GPU" FontSize="15" FontWeight="SemiBold"/>
                <TextBlock x:Name="McJavaInfo" TextWrapping="Wrap" Foreground="#9AA6C4" Margin="0,4,0,10"/>
                <StackPanel Orientation="Horizontal"><Button x:Name="BtnMcGpu" Style="{StaticResource BtnPrimary}" Content="Find Java + set High Performance GPU"/></StackPanel>
              </StackPanel>
            </Border>
            <Border Background="#0F1626" BorderBrush="#1C2740" BorderThickness="1" CornerRadius="12" Padding="18" Margin="0,0,0,12">
              <StackPanel>
                <TextBlock Text="2. Install performance mods (Fabric)" FontSize="15" FontWeight="SemiBold"/>
                <TextBlock TextWrapping="Wrap" Foreground="#9AA6C4" Margin="0,4,0,0" Text="Sodium rewrites the renderer and routinely gives 2-5x FPS on its own. This is the single biggest win available - bigger than every Windows tweak combined."/>
                <TextBlock TextWrapping="Wrap" Margin="0,8,0,0" FontFamily="Consolas" Foreground="#00E5FF"
                  Text="Sodium  -  Lithium  -  FerriteCore  -  ImmediatelyFast  -  EntityCulling  -  ModernFix  -  More Culling"/>
                <StackPanel Orientation="Horizontal" Margin="0,12,0,0"><Button x:Name="BtnModrinth" Style="{StaticResource Btn}" Content="Open Sodium on Modrinth"/></StackPanel>
              </StackPanel>
            </Border>
            <Border Background="#0F1626" BorderBrush="#1C2740" BorderThickness="1" CornerRadius="12" Padding="18">
              <StackPanel>
                <TextBlock Text="3. JVM arguments tuned for your RAM" FontSize="15" FontWeight="SemiBold"/>
                <TextBlock x:Name="McRamInfo" TextWrapping="Wrap" Foreground="#9AA6C4" Margin="0,4,0,8"/>
                <TextBox x:Name="McArgs" IsReadOnly="True" TextWrapping="Wrap" FontFamily="Consolas" FontSize="12" Background="#070B14" Foreground="#DCE3F5" BorderBrush="#1C2740" Padding="10"/>
                <StackPanel Orientation="Horizontal" Margin="0,10,0,0"><Button x:Name="BtnCopyArgs" Style="{StaticResource Btn}" Content="Copy to clipboard"/></StackPanel>
                <TextBlock TextWrapping="Wrap" Foreground="#7F8BA8" FontSize="12" Margin="0,8,0,0" Text="Launcher -> Installations -> Edit -> More options -> JVM arguments. More RAM is NOT faster: too much causes long GC stutters."/>
              </StackPanel>
            </Border>
          </StackPanel>
        </ScrollViewer>

        <!-- LOG -->
        <TextBox x:Name="PageLog" Visibility="Collapsed" IsReadOnly="True" FontFamily="Consolas" FontSize="12"
                 Background="#0A0F1C" Foreground="#9AE6FF" BorderBrush="#1C2740" Padding="12"
                 VerticalScrollBarVisibility="Auto" TextWrapping="Wrap"/>
      </Grid>

      <!-- status bar -->
      <Border Grid.Row="2" Grid.ColumnSpan="2" Background="#090E1A" BorderBrush="#141D33" BorderThickness="0,1,0,0">
        <TextBlock x:Name="Status" Margin="16,0" VerticalAlignment="Center" FontSize="12" Foreground="#7F8BA8" Text="Ready."/>
      </Border>
    </Grid>
  </Border>
</Window>
'@

$window = [Windows.Markup.XamlReader]::Load((New-Object System.Xml.XmlNodeReader $xaml))
$ui = @{}
$xaml.SelectNodes('//*[@*[local-name()="Name"]]') | ForEach-Object {
    $n = $_.Attributes | Where-Object { $_.LocalName -eq 'Name' } | Select-Object -First 1
    $ui[$n.Value] = $window.FindName($n.Value)
}

# Build a seamless repeating wave (one hump up, one down per period) wider than any window
function New-WavePath([double]$amp, [double]$period, [int]$width, [int]$height) {
    $sb = New-Object Text.StringBuilder
    [void]$sb.Append("M0,$height L0,$amp ")
    $h = $period / 2
    for ($x = 0; $x -lt $width; $x += $period) {
        [void]$sb.Append(("Q{0},{1} {2},{3} " -f ($x + $h / 2), (-$amp), ($x + $h), $amp))
        [void]$sb.Append(("Q{0},{1} {2},{3} " -f ($x + $h * 1.5), (3 * $amp), ($x + $period), $amp))
    }
    [void]$sb.Append("L$width,$height Z")
    [Windows.Media.Geometry]::Parse($sb.ToString())
}
# Taskbar / Alt-Tab icon rendered from the same vector logo
try {
    $dv = New-Object Windows.Media.DrawingVisual; $dc = $dv.RenderOpen()
    $dc.DrawImage($window.FindResource('Logo'), (New-Object Windows.Rect 0, 0, 64, 64)); $dc.Close()
    $rtb = New-Object Windows.Media.Imaging.RenderTargetBitmap 64, 64, 96, 96, ([Windows.Media.PixelFormats]::Pbgra32)
    $rtb.Render($dv)
    $window.Icon = [Windows.Media.Imaging.BitmapFrame]::Create($rtb)
} catch { }
$ui.VersionText.Text = "v$script:Version"
$ui.Wave1.Data = New-WavePath 22 400 4400 80
$ui.Wave2.Data = New-WavePath 16 200 4400 80
#endregion

#region ---------- logging / UI helpers ----------
function Write-Log([string]$msg) {
    $line = '[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $msg
    $ui.PageLog.AppendText($line + "`r`n")
    $ui.PageLog.ScrollToEnd()
    $ui.Status.Text = $msg
    Add-Content -Path (Join-Path $script:DataDir 'wave.log') -Value $line -ErrorAction SilentlyContinue
    if ($script:Headless) { Write-Host "  log: $msg" }
}

function Update-UI { $window.Dispatcher.Invoke([Action]{}, [Windows.Threading.DispatcherPriority]::Background) }

function New-Brush([string]$hex) { (New-Object Windows.Media.BrushConverter).ConvertFromString($hex) }

function New-Card {
    $b = New-Object Windows.Controls.Border
    $b.Background = $window.FindResource('Card'); $b.BorderBrush = $window.FindResource('CardBorder')
    $b.BorderThickness = 1; $b.CornerRadius = 12; $b.Padding = '16,14'; $b.Margin = '0,0,10,10'
    $b
}

function New-Text([string]$text, [double]$size = 13, [string]$color = '#DCE3F5', [string]$weight = 'Normal') {
    $t = New-Object Windows.Controls.TextBlock
    $t.Text = $text; $t.FontSize = $size; $t.Foreground = New-Brush $color; $t.FontWeight = $weight; $t.TextWrapping = 'Wrap'
    $t
}
#endregion

#region ---------- hardware scan ----------
function Get-Specs {
    $s = [ordered]@{}
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $s.CPU      = $cpu.Name.Trim() -replace '\s+', ' '
    $s.CPUInfo  = '{0} cores / {1} threads  -  {2:N1} GHz base' -f $cpu.NumberOfCores, $cpu.NumberOfLogicalProcessors, ($cpu.MaxClockSpeed / 1000)

    # GPUs: AdapterRAM caps at 4 GB, so read the real VRAM from the driver key
    $vramByName = @{}
    Get-ChildItem 'HKLM:\SYSTEM\ControlSet001\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}' -ErrorAction SilentlyContinue |
        Where-Object { $_.PSChildName -match '^\d{4}$' } | ForEach-Object {
            $p = Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue
            $q = $p.'HardwareInformation.qwMemorySize'
            if ($p.DriverDesc -and $q) { $vramByName[$p.DriverDesc] = [uint64]$q }
        }
    $gpus = @(Get-CimInstance Win32_VideoController | Where-Object { $_.Name -notmatch 'Basic Display|Remote|Virtual|Parsec|Meta' })
    $s.GPUs = @(foreach ($g in $gpus) {
        $vram = if ($vramByName.ContainsKey($g.Name)) { $vramByName[$g.Name] } else { [uint64]$g.AdapterRAM }
        [pscustomobject]@{
            Name = $g.Name; VRAMGB = [math]::Round($vram / 1GB, 1); Driver = $g.DriverVersion; DriverDate = $g.DriverDate
            Integrated = ($g.Name -match 'Intel\(R\) (UHD|HD|Iris)|Radeon\(TM\) Graphics|Radeon Graphics|Vega \d+ Graphics' -and $g.Name -notmatch 'Arc')
            Hz = $g.CurrentRefreshRate; MaxHz = $g.MaxRefreshRate
            ResX = $g.CurrentHorizontalResolution; ResY = $g.CurrentVerticalResolution
        }
    })

    $mem = @(Get-CimInstance Win32_PhysicalMemory)
    $s.RAMGB      = [math]::Round((($mem | Measure-Object Capacity -Sum).Sum) / 1GB)
    $s.RAMSticks  = $mem.Count
    $s.RAMSpeed   = ($mem | Measure-Object ConfiguredClockSpeed -Minimum).Minimum
    $s.RAMRated   = ($mem | Measure-Object Speed -Maximum).Maximum
    $memType      = ($mem | Select-Object -First 1).SMBIOSMemoryType
    $s.RAMType    = switch ($memType) { 26 { 'DDR4' } 34 { 'DDR5' } 24 { 'DDR3' } default { 'DDR' } }

    $os = Get-CimInstance Win32_OperatingSystem
    $s.OS     = $os.Caption -replace 'Microsoft ', ''
    $s.Build  = $os.BuildNumber
    $s.FreeRAMGB = [math]::Round($os.FreePhysicalMemory / 1MB, 1)

    $board = Get-CimInstance Win32_BaseBoard
    $s.Board = ('{0} {1}' -f $board.Manufacturer, $board.Product).Trim()

    $sysLetter = $env:SystemDrive.TrimEnd(':')
    $s.DiskKind = 'Unknown'
    try {
        $diskNum = (Get-Partition -DriveLetter $sysLetter).DiskNumber
        $pd = Get-PhysicalDisk | Where-Object { $_.DeviceId -eq "$diskNum" } | Select-Object -First 1
        $s.DiskKind = if ($pd.BusType -eq 'NVMe') { 'NVMe SSD' } elseif ($pd.MediaType -eq 'SSD') { 'SATA SSD' } elseif ($pd.MediaType -eq 'HDD') { 'HDD' } else { "$($pd.MediaType) $($pd.BusType)" }
    } catch { }
    $ld = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='$env:SystemDrive'"
    $s.DiskSizeGB = [math]::Round($ld.Size / 1GB)
    $s.DiskFreeGB = [math]::Round($ld.FreeSpace / 1GB)

    $bat = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1
    $s.Laptop    = [bool]$bat
    $s.OnBattery = ($bat -and $bat.BatteryStatus -eq 1)
    $s.PowerPlan = if ("$(powercfg /getactivescheme)" -match '\((.+)\)') { $Matches[1] } else { '?' }
    $s
}

function Add-SpecCard([string]$label, [string]$main, [string]$sub) {
    $card = New-Card
    $sp = New-Object Windows.Controls.StackPanel
    $sp.Children.Add((New-Text $label 10 '#7F8BA8' 'Bold')) | Out-Null
    $m = New-Text $main 16 '#FFFFFF' 'SemiBold'; $m.Margin = '0,6,0,2'
    $sp.Children.Add($m) | Out-Null
    $sp.Children.Add((New-Text $sub 12 '#9AA6C4')) | Out-Null
    $card.Child = $sp
    $ui.SpecGrid.Children.Add($card) | Out-Null
}

function Add-Rec([string]$level, [string]$title, [string]$body) {
    $color = switch ($level) { 'big' { '#FF4D7A' } 'mid' { '#FFB547' } default { '#3DF5A0' } }
    $tag   = switch ($level) { 'big' { 'HIGH IMPACT' } 'mid' { 'MEDIUM' } default { 'GOOD' } }
    $card = New-Card; $card.Margin = '0,0,0,10'; $card.BorderBrush = New-Brush ($color -replace '#', '#55')
    $g = New-Object Windows.Controls.DockPanel
    $badge = New-Object Windows.Controls.Border
    $badge.CornerRadius = 6; $badge.Padding = '8,3'; $badge.Margin = '0,0,14,0'; $badge.VerticalAlignment = 'Top'
    $badge.Background = New-Brush ($color -replace '#', '#22')
    $badge.Child = (New-Text $tag 10 $color 'Bold')
    [Windows.Controls.DockPanel]::SetDock($badge, 'Left')
    $g.Children.Add($badge) | Out-Null
    $sp = New-Object Windows.Controls.StackPanel
    $sp.Children.Add((New-Text $title 14 '#FFFFFF' 'SemiBold')) | Out-Null
    $b = New-Text $body 12 '#9AA6C4'; $b.Margin = '0,3,0,0'
    $sp.Children.Add($b) | Out-Null
    $g.Children.Add($sp) | Out-Null
    $card.Child = $g
    $ui.RecList.Children.Add($card) | Out-Null
}

function Show-Dashboard {
    $s = $script:Specs
    $ui.SpecGrid.Children.Clear(); $ui.RecList.Children.Clear()

    $mainGpu = $s.GPUs | Sort-Object { -not $_.Integrated }, VRAMGB -Descending | Select-Object -First 1
    Add-SpecCard 'PROCESSOR' $s.CPU $s.CPUInfo
    if ($mainGpu) { Add-SpecCard 'GRAPHICS' $mainGpu.Name ('{0} GB VRAM  -  driver {1}' -f $mainGpu.VRAMGB, $mainGpu.Driver) }
    else          { Add-SpecCard 'GRAPHICS' 'Not detected' '' }
    Add-SpecCard 'MEMORY' ('{0} GB {1}' -f $s.RAMGB, $s.RAMType) ('{0} stick(s) @ {1} MT/s  -  {2} GB free' -f $s.RAMSticks, $s.RAMSpeed, $s.FreeRAMGB)
    Add-SpecCard 'SYSTEM DRIVE' $s.DiskKind ('{0} GB free of {1} GB' -f $s.DiskFreeGB, $s.DiskSizeGB)
    if ($mainGpu -and $mainGpu.ResX) { Add-SpecCard 'DISPLAY' ('{0}x{1} @ {2} Hz' -f $mainGpu.ResX, $mainGpu.ResY, $mainGpu.Hz) ("Power plan: $($s.PowerPlan)") }
    Add-SpecCard 'WINDOWS' $s.OS ("Build $($s.Build)  -  $($s.Board)")

    $ui.HeaderSub.Text = '{0}  |  {1}  |  {2} GB {3}  |  {4}' -f $s.CPU, ($(if ($mainGpu) { $mainGpu.Name } else { 'GPU ?' })), $s.RAMGB, $s.RAMType, $s.OS

    # ---- honest bottleneck analysis ----
    $recs = 0
    if ($s.GPUs.Count -gt 1 -and ($s.GPUs | Where-Object Integrated) -and ($s.GPUs | Where-Object { -not $_.Integrated })) {
        Add-Rec 'big' 'You have two GPUs - make sure games use the fast one' 'Integrated + dedicated GPU detected. Games launched on the integrated chip run 3-5x slower. Use the "GPU & Games" tab to pin your games (and Minecraft) to the dedicated GPU.'; $recs++
    } elseif ($mainGpu -and $mainGpu.Integrated) {
        Add-Rec 'mid' 'Integrated graphics only' 'Your GPU is the main limit. Lower resolution / render scale, use FSR if the game has it, and make sure RAM is dual-channel - iGPUs use system RAM as VRAM.'; $recs++
    }
    if ($s.RAMSticks -eq 1) {
        Add-Rec 'big' 'Single-channel RAM' 'Only one RAM stick detected. Adding a matching second stick (dual channel) is often +20-50% FPS in CPU-bound games and huge for integrated graphics.'; $recs++
    }
    $jedec = ($s.RAMType -eq 'DDR4' -and $s.RAMSpeed -le 2666) -or ($s.RAMType -eq 'DDR5' -and $s.RAMSpeed -le 4800)
    if (-not $s.Laptop -and (($s.RAMRated -and $s.RAMSpeed -lt $s.RAMRated) -or $jedec)) {
        Add-Rec 'big' "RAM running at $($s.RAMSpeed) MT/s - XMP/EXPO is probably OFF" 'Enable XMP (Intel) or EXPO/DOCP (AMD) in your BIOS. Free 10-20% FPS in CPU-limited games, zero risk on supported kits.'; $recs++
    }
    if ($mainGpu -and $mainGpu.MaxHz -and $mainGpu.Hz -and $mainGpu.Hz -lt $mainGpu.MaxHz -and $mainGpu.Hz -le 75) {
        Add-Rec 'big' "Monitor running at $($mainGpu.Hz) Hz" "Your display reports up to $($mainGpu.MaxHz) Hz. Settings -> Display -> Advanced display -> Choose a refresh rate. You literally can't see more than $($mainGpu.Hz) FPS right now."; $recs++
    }
    if ($s.OnBattery) {
        Add-Rec 'big' 'Laptop is running on battery' 'Plug in the charger. On battery the CPU and GPU are power-limited, often to half performance or worse.'; $recs++
    }
    if ($s.DiskKind -eq 'HDD') {
        Add-Rec 'mid' 'Windows is on a hard drive' 'An SSD will not raise average FPS much, but it removes loading stutter and makes the whole PC feel 5x faster.'; $recs++
    }
    if ($s.DiskSizeGB -gt 0 -and ($s.DiskFreeGB / $s.DiskSizeGB) -lt 0.12) {
        Add-Rec 'mid' 'System drive almost full' "Only $($s.DiskFreeGB) GB free. Windows and shader caches slow down when the drive is nearly full - run Cleanup."; $recs++
    }
    if ($mainGpu -and $mainGpu.DriverDate -and ((Get-Date) - $mainGpu.DriverDate).TotalDays -gt 180) {
        Add-Rec 'mid' 'Graphics driver is over 6 months old' ("Installed driver is from {0:yyyy-MM-dd}. New drivers regularly add 5-15% in recent games - grab the latest from NVIDIA / AMD / Intel." -f $mainGpu.DriverDate); $recs++
    }
    if ($s.RAMGB -lt 16) {
        Add-Rec 'mid' "Only $($s.RAMGB) GB RAM" '16 GB is the comfortable minimum for modern games with Discord/browser open. Close background apps before playing.'; $recs++
    }
    if (-not (Test-PerfPlan)) {
        Add-Rec 'mid' "Power plan: $($s.PowerPlan)" 'Turn on "Ultimate Performance power plan" in Tweaks so the CPU stops downclocking.'; $recs++
    }
    if ($recs -eq 0) { Add-Rec 'ok' 'No hardware bottlenecks detected' 'Your setup looks well configured. Apply the Gaming preset in Tweaks for the last few percent.' }
}
#endregion

#region ---------- tweak engine ----------
$script:Toggles    = @{}
$script:State      = @{}
$script:CardById   = @{}
$script:CatHeaders = [ordered]@{}
$script:Visible    = @()
$script:CatFilter  = 'All'

function Disable-MMAFeature($f) { Backup-Special "mma|$f" ([bool](Get-MMAgent).$f); $p = @{ $f = $true }; Disable-MMAgent @p }
function Restore-MMAFeature($f) { $b = Pop-Special "mma|$f"; if ($b -and $b.Value) { $p = @{ $f = $true }; Enable-MMAgent @p } }

function Set-NicKeyword($Keyword, $Value) {
    foreach ($n in @(Get-NicProps $Keyword)) {
        Backup-Special "nic|$($n.Adapter.InterfaceGuid)|$Keyword" "$($n.Prop.RegistryValue)"
        Set-NetAdapterAdvancedProperty -Name $n.Adapter.Name -RegistryKeyword $Keyword -RegistryValue $Value
    }
}
function Restore-NicKeyword($Keyword) {
    foreach ($n in @(Get-NicProps $Keyword)) {
        $b = Pop-Special "nic|$($n.Adapter.InterfaceGuid)|$Keyword"
        if ($b) { Set-NetAdapterAdvancedProperty -Name $n.Adapter.Name -RegistryKeyword $Keyword -RegistryValue $b.Value }
    }
}

function Get-SvcTarget($t) { if ($t.SvcStart) { $t.SvcStart } else { 4 } }

# Should this tweak be shown on this PC at all? (service installed, setting exists, Windows 11, ...)
function Test-Applies($t) {
    try {
        if ($t.Applies -and -not (& $t.Applies)) { return $false }
        if ($t.Svc -and -not ($t.Svc | Where-Object { Test-ServiceExists $_ })) { return $false }
        if ($t.Tasks -and @(Get-TaskMatches $t.Tasks).Count -eq 0) { return $false }
        if ($t.Pwr -and $null -eq (Get-PwrAc $t.Pwr[0].Sub $t.Pwr[0].Set)) { return $false }
        if ($t.Nic -and @($t.Nic.K | ForEach-Object { Get-NicProps $_ }).Count -eq 0) { return $false }
        if ($t.MMA -and -not (Get-Command Get-MMAgent -ErrorAction SilentlyContinue)) { return $false }
        return $true
    } catch { return $false }
}

# Is the tweak currently active on the system? Every part it has must be in the tweaked state.
function Test-Tweak($t) {
    try {
        if ($t.Reg) { foreach ($r in $t.Reg) { if ("$(Get-RegValue $r.P $r.N)" -ne "$($r.V)") { return $false } } }
        if ($t.Svc) {
            $want = Get-SvcTarget $t
            $ex = @($t.Svc | Where-Object { Test-ServiceExists $_ })
            if ($ex.Count -eq 0) { return $false }
            foreach ($s in $ex) { if ((Get-RegValue "HKLM:\SYSTEM\CurrentControlSet\Services\$s" 'Start') -ne $want) { return $false } }
        }
        if ($t.Tasks) {
            $m = @(Get-TaskMatches $t.Tasks)
            if ($m.Count -eq 0 -or ($m | Where-Object { "$($_.State)" -ne 'Disabled' })) { return $false }
        }
        if ($t.Pwr) { foreach ($p in $t.Pwr) { if ((Get-PwrAc $p.Sub $p.Set) -ne $p.V) { return $false } } }
        if ($t.Nic) {
            $n = @($t.Nic.K | ForEach-Object { Get-NicProps $_ })
            if ($n.Count -eq 0 -or ($n | Where-Object { "$($_.Prop.RegistryValue)" -ne $t.Nic.V })) { return $false }
        }
        if ($t.MMA -and (Get-MMAgent).($t.MMA)) { return $false }
        if ($t.Check -and -not (& $t.Check)) { return $false }
        return $true
    } catch { return $false }
}

function Invoke-TweakApply($t) {
    if ($t.Reg) { foreach ($r in $t.Reg) { Set-Reg $r.P $r.N $r.V $r.T } }
    if ($t.Svc) { $want = Get-SvcTarget $t; foreach ($s in $t.Svc) { Set-ServiceStart $s $want } }
    if ($t.Tasks) {
        foreach ($task in @(Get-TaskMatches $t.Tasks)) {
            try {
                Backup-Special "task|$($task.TaskPath)$($task.TaskName)" ("$($task.State)" -ne 'Disabled')
                Disable-ScheduledTask -TaskPath $task.TaskPath -TaskName $task.TaskName | Out-Null
            } catch { Write-Log "     could not disable task $($task.TaskName): $($_.Exception.Message)" }
        }
    }
    if ($t.Pwr) {
        foreach ($p in $t.Pwr) {
            Backup-Special "pwr|$($p.Sub)|$($p.Set)" (Get-PwrAc $p.Sub $p.Set)
            powercfg -setacvalueindex SCHEME_CURRENT $p.Sub $p.Set $p.V | Out-Null
        }
        powercfg /setactive SCHEME_CURRENT | Out-Null
    }
    if ($t.Nic) { foreach ($k in $t.Nic.K) { Set-NicKeyword $k $t.Nic.V } }
    if ($t.MMA) { Disable-MMAFeature $t.MMA }
    if ($t.Apply) { & $t.Apply }
}

function Invoke-TweakRevert($t) {
    if ($t.Revert) { & $t.Revert }
    if ($t.MMA) { Restore-MMAFeature $t.MMA }
    if ($t.Nic) { foreach ($k in $t.Nic.K) { Restore-NicKeyword $k } }
    if ($t.Pwr) {
        foreach ($p in $t.Pwr) {
            $b = Pop-Special "pwr|$($p.Sub)|$($p.Set)"
            if ($b -and $null -ne $b.Value) { powercfg -setacvalueindex SCHEME_CURRENT $p.Sub $p.Set $b.Value | Out-Null }
        }
        powercfg /setactive SCHEME_CURRENT | Out-Null
    }
    if ($t.Tasks) {
        foreach ($task in @(Get-TaskMatches $t.Tasks)) {
            $b = Pop-Special "task|$($task.TaskPath)$($task.TaskName)"
            if ($b -and $b.Value) { try { Enable-ScheduledTask -TaskPath $task.TaskPath -TaskName $task.TaskName | Out-Null } catch { } }
        }
    }
    if ($t.Svc) { foreach ($s in $t.Svc) { Restore-ServiceStart $s } }
    if ($t.Reg) { foreach ($r in $t.Reg) { Restore-Reg $r.P $r.N } }
}

function Update-States {
    try { $script:AllTasks = @(Get-ScheduledTask -ErrorAction SilentlyContinue) } catch { $script:AllTasks = @() }
    foreach ($t in $script:Visible) { $script:State[$t.Id] = Test-Tweak $t }
}

function Sync-Toggles { foreach ($t in $script:Visible) { $script:Toggles[$t.Id].IsChecked = [bool]$script:State[$t.Id] }; Update-Pending }

function New-SmallButton([string]$text, $tag) {
    $b = New-Object Windows.Controls.Button
    $b.Style = $window.FindResource('Btn'); $b.Content = $text; $b.Tag = $tag
    $b.Padding = '10,3'; $b.FontSize = 11; $b.Margin = '6,0,0,0'
    $b
}

function Build-TweakList {
    Write-Log 'Checking which tweaks apply to this PC...'; Update-UI
    try { $script:NetAdapters = @(Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq 'Up' }) } catch { $script:NetAdapters = @() }
    try { $script:AllTasks = @(Get-ScheduledTask -ErrorAction SilentlyContinue) } catch { $script:AllTasks = @() }
    $script:Visible = @($script:Tweaks | Where-Object { Test-Applies $_ })
    $hidden = $script:Tweaks.Count - $script:Visible.Count
    Write-Log "$($script:Visible.Count) tweaks apply to this PC ($hidden skipped: not installed / not supported here)."

    $ui.TweakList.Children.Clear(); $script:CatHeaders.Clear(); $script:CardById.Clear(); $script:Toggles.Clear()
    $cats = @($script:Visible | ForEach-Object { $_.Cat } | Select-Object -Unique)
    $done = 0
    foreach ($cat in $cats) {
        $items = @($script:Visible | Where-Object { $_.Cat -eq $cat })

        $hdr = New-Object Windows.Controls.DockPanel; $hdr.Margin = '2,14,0,8'
        $bOff = New-SmallButton 'All off' $cat; [Windows.Controls.DockPanel]::SetDock($bOff, 'Right')
        $bOn  = New-SmallButton 'All on'  $cat; [Windows.Controls.DockPanel]::SetDock($bOn, 'Right')
        $bOn.Add_Click({  param($s) foreach ($t in @($script:Visible | Where-Object { $_.Cat -eq $s.Tag })) { $script:Toggles[$t.Id].IsChecked = $true };  Update-Pending })
        $bOff.Add_Click({ param($s) foreach ($t in @($script:Visible | Where-Object { $_.Cat -eq $s.Tag })) { $script:Toggles[$t.Id].IsChecked = $false }; Update-Pending })
        $hdr.Children.Add($bOff) | Out-Null; $hdr.Children.Add($bOn) | Out-Null
        $title = New-Text ('{0}   {1}' -f $cat.ToUpper(), $items.Count) 12 '#7F8BA8' 'Bold'; $title.VerticalAlignment = 'Center'
        $hdr.Children.Add($title) | Out-Null
        $ui.TweakList.Children.Add($hdr) | Out-Null
        $script:CatHeaders[$cat] = $hdr

        foreach ($t in $items) {
            $script:State[$t.Id] = Test-Tweak $t
            $card = New-Card; $card.Margin = '0,0,0,8'; $card.Padding = '16,12'
            $dp = New-Object Windows.Controls.DockPanel
            $tg = New-Object Windows.Controls.Primitives.ToggleButton
            $tg.Style = $window.FindResource('Switch'); $tg.VerticalAlignment = 'Center'; $tg.Margin = '0,0,16,0'
            $tg.IsChecked = [bool]$script:State[$t.Id]
            $tg.Add_Click({ Update-Pending })
            [Windows.Controls.DockPanel]::SetDock($tg, 'Left')
            $dp.Children.Add($tg) | Out-Null

            $riskColor = switch ($t.Risk) { 'Safe' { '#3DF5A0' } 'Moderate' { '#FFB547' } default { '#FF4D7A' } }
            $badge = New-Object Windows.Controls.Border
            $badge.CornerRadius = 6; $badge.Padding = '8,3'; $badge.VerticalAlignment = 'Center'; $badge.Margin = '12,0,0,0'
            $badge.Background = New-Brush ($riskColor -replace '#', '#22')
            $badgeText = $t.Risk.ToUpper(); if ($t.Reboot) { $badgeText += '  -  REBOOT' }
            $badge.Child = New-Text $badgeText 10 $riskColor 'Bold'
            [Windows.Controls.DockPanel]::SetDock($badge, 'Right')
            $dp.Children.Add($badge) | Out-Null

            $sp = New-Object Windows.Controls.StackPanel
            $sp.Children.Add((New-Text $t.Name 14 '#FFFFFF' 'SemiBold')) | Out-Null
            $d = New-Text $t.Desc 12 '#9AA6C4'; $d.Margin = '0,3,0,0'
            $sp.Children.Add($d) | Out-Null
            $dp.Children.Add($sp) | Out-Null
            $card.Child = $dp
            $ui.TweakList.Children.Add($card) | Out-Null
            $script:Toggles[$t.Id] = $tg
            $script:CardById[$t.Id] = $card
            $done++
        }
        $ui.Status.Text = "Checking tweaks... $done / $($script:Visible.Count)"; Update-UI
    }
    Build-Chips
    Update-Count
    Update-Pending
    Update-TweakFilter
}

function Build-Chips {
    $ui.CatChips.Children.Clear()
    foreach ($c in (@('All') + @($script:CatHeaders.Keys))) {
        $n = if ($c -eq 'All') { $script:Visible.Count } else { @($script:Visible | Where-Object { $_.Cat -eq $c }).Count }
        $b = New-SmallButton "$c  $n" $c; $b.Margin = '0,0,6,6'; $b.Padding = '12,5'
        $b.Add_Click({ param($s) $script:CatFilter = $s.Tag; Update-Chips; Update-TweakFilter })
        $ui.CatChips.Children.Add($b) | Out-Null
    }
    Update-Chips
}

function Update-Chips {
    foreach ($b in $ui.CatChips.Children) {
        $b.Background = if ($b.Tag -eq $script:CatFilter) { $window.FindResource('WaveGrad') } else { New-Brush '#16213A' }
    }
}

function Update-TweakFilter {
    $q = "$($ui.TweakSearch.Text)".Trim()
    $ui.SearchHint.Visibility = if ($q) { 'Collapsed' } else { 'Visible' }
    $cmp = [StringComparison]::OrdinalIgnoreCase
    foreach ($cat in @($script:CatHeaders.Keys)) {
        $any = $false
        foreach ($t in @($script:Visible | Where-Object { $_.Cat -eq $cat })) {
            $show = ($script:CatFilter -eq 'All' -or $script:CatFilter -eq $cat) -and
                    (-not $q -or $t.Name.IndexOf($q, $cmp) -ge 0 -or $t.Desc.IndexOf($q, $cmp) -ge 0 -or $t.Cat.IndexOf($q, $cmp) -ge 0)
            $script:CardById[$t.Id].Visibility = if ($show) { 'Visible' } else { 'Collapsed' }
            if ($show) { $any = $true }
        }
        $script:CatHeaders[$cat].Visibility = if ($any) { 'Visible' } else { 'Collapsed' }
    }
}

function Update-Count {
    $on = @($script:Visible | Where-Object { $script:State[$_.Id] }).Count
    $all = $script:Visible.Count
    $ui.SideCount.Text = "$on / $all"
    $ui.SideBar.Value = if ($all) { [math]::Round(100 * $on / $all) } else { 0 }
    $ui.TweakCount.Text = "$all tweaks available on this PC  -  $on active"
}

function Update-Pending {
    $n = 0
    foreach ($t in $script:Visible) {
        $pending = [bool]$script:Toggles[$t.Id].IsChecked -ne [bool]$script:State[$t.Id]
        if ($pending) { $n++ }
        $script:CardById[$t.Id].BorderBrush = if ($pending) { New-Brush '#00E5FF' } else { $window.FindResource('CardBorder') }
    }
    $ui.BtnApply.Content = if ($n) { "APPLY $n CHANGE$(if ($n -ne 1) { 'S' })" } else { 'APPLY CHANGES' }
}

function Set-Preset([string]$letter) {
    foreach ($t in $script:Visible) { $script:Toggles[$t.Id].IsChecked = $t.Presets.Contains($letter) }
    Update-Pending
    Write-Log 'Preset loaded - changed switches are outlined in cyan. Review them, then press APPLY.'
}

function New-RestorePoint {
    Write-Log 'Creating system restore point...'; Update-UI
    try {
        Enable-ComputerRestore -Drive "$env:SystemDrive\" -ErrorAction SilentlyContinue
        $srKey = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\SystemRestore'
        $old = Get-RegValue $srKey 'SystemRestorePointCreationFrequency'
        New-ItemProperty -Path $srKey -Name 'SystemRestorePointCreationFrequency' -Value 0 -PropertyType DWord -Force | Out-Null
        Checkpoint-Computer -Description 'WaveOptimizer' -RestorePointType 'MODIFY_SETTINGS'
        if ($null -eq $old) { Remove-ItemProperty -Path $srKey -Name 'SystemRestorePointCreationFrequency' -ErrorAction SilentlyContinue }
        else { Set-ItemProperty -Path $srKey -Name 'SystemRestorePointCreationFrequency' -Value $old }
        Write-Log 'Restore point created.'
        return $true
    } catch {
        Write-Log "Restore point failed: $($_.Exception.Message)"
        return $false
    }
}

function Invoke-ApplyChanges {
    $todo = @($script:Visible | Where-Object { [bool]$script:Toggles[$_.Id].IsChecked -ne [bool]$script:State[$_.Id] })
    if ($todo.Count -eq 0) { Write-Log 'Nothing to change - every switch already matches your system.'; return }

    $turnOn = @($todo | Where-Object { $script:Toggles[$_.Id].IsChecked })
    $risky  = @($turnOn | Where-Object { $_.Risk -ne 'Safe' })
    $msg = "Apply $($todo.Count) change(s)?  ($($turnOn.Count) on, $($todo.Count - $turnOn.Count) off)"
    if ($risky.Count) {
        $msg += "`n`nIncludes $($risky.Count) Moderate/Advanced tweak(s):`n - " + (($risky | Select-Object -First 10 | ForEach-Object { $_.Name }) -join "`n - ")
        if ($risky.Count -gt 10) { $msg += "`n - ...and $($risky.Count - 10) more" }
    }
    if ([Windows.MessageBox]::Show($msg, 'WaveOptimizer', 'YesNo', 'Question') -ne 'Yes') { return }

    if ($ui.ChkRestore.IsChecked) {
        if (-not (New-RestorePoint)) {
            $r = [Windows.MessageBox]::Show('Could not create a restore point. Continue anyway? (Every change is still backed up and revertible.)', 'WaveOptimizer', 'YesNo', 'Warning')
            if ($r -ne 'Yes') { return }
        }
    }

    # the power plan must switch first, otherwise power settings land on the old plan
    $todo = @($todo | Where-Object { $_.Id -eq 'powerplan' }) + @($todo | Where-Object { $_.Id -ne 'powerplan' })
    $reboot = $false; $i = 0
    foreach ($t in $todo) {
        $i++
        $want = [bool]$script:Toggles[$t.Id].IsChecked
        $ui.Status.Text = "Applying $i / $($todo.Count): $($t.Name)"
        try {
            if ($want) { Invoke-TweakApply $t; Write-Log "ON   $($t.Name)" }
            else       { Invoke-TweakRevert $t; Write-Log "OFF  $($t.Name)" }
            if ($t.Reboot) { $reboot = $true }
        } catch { Write-Log "FAIL $($t.Name): $($_.Exception.Message)" }
        Update-UI
    }

    Update-States
    foreach ($t in $todo) {
        $want = [bool]$script:Toggles[$t.Id].IsChecked
        if ([bool]$script:State[$t.Id] -ne $want) {
            if ($want) { Write-Log "NOTE $($t.Name): did not stick (blocked by Windows, your edition or a policy)." }
            else       { Write-Log "NOTE $($t.Name): it was already set like this before WaveOptimizer, so there is nothing to restore." }
        }
    }
    Sync-Toggles
    Update-Count
    try { $script:Specs.PowerPlan = if ("$(powercfg /getactivescheme)" -match '\((.+)\)') { $Matches[1] } else { '?' }; Show-Dashboard } catch { }

    $msg = "Done - $($todo.Count) change(s) processed. See the Log tab for details."
    if ($reboot) { $msg += "`n`nSome changes need a REBOOT to take effect." }
    Write-Log $msg.Replace("`n", ' ')
    if ($todo | Where-Object { $_.Cat -in 'Explorer & Taskbar', 'Windows Visuals', 'Ads & Notifications' }) {
        $msg += "`n`nRestart Explorer now so taskbar/desktop changes show up? (Open folders will close.)"
        if ([Windows.MessageBox]::Show($msg, 'WaveOptimizer', 'YesNo', 'Information') -eq 'Yes') {
            Stop-Process -Name explorer -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 800
            if (-not (Get-Process explorer -ErrorAction SilentlyContinue)) { Start-Process explorer.exe }
            Write-Log 'Explorer restarted.'
        }
    } else {
        [Windows.MessageBox]::Show($msg, 'WaveOptimizer', 'OK', 'Information') | Out-Null
    }
}

function Invoke-RevertAll {
    $r = [Windows.MessageBox]::Show('Restore every setting WaveOptimizer changed back to its original value?', 'WaveOptimizer', 'YesNo', 'Question')
    if ($r -ne 'Yes') { return }
    $i = 0
    foreach ($t in $script:Tweaks) {
        $i++; $ui.Status.Text = "Reverting $i / $($script:Tweaks.Count)..."
        try { Invoke-TweakRevert $t } catch { Write-Log "FAIL revert $($t.Name): $($_.Exception.Message)" }
        if ($i % 10 -eq 0) { Update-UI }
    }
    # anything left over in the backup (e.g. network interfaces that changed) gets restored too
    foreach ($k in @($script:Backup.Keys)) {
        if ($k -like 'HK*|*') { $parts = $k -split '\|', 2; try { Restore-Reg $parts[0] $parts[1] } catch { } }
    }
    Update-States
    Sync-Toggles
    Update-Count
    Write-Log 'All settings reverted to their originals. Reboot recommended.'
}
#endregion

#region ---------- cleanup ----------
function Get-FolderBytes($path) {
    if (-not (Test-Path $path)) { return 0 }
    $sum = (Get-ChildItem $path -Recurse -Force -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    if ($sum) { [int64]$sum } else { 0 }
}

function Clear-Folder($path) {
    if (-not (Test-Path $path)) { return 0 }
    $before = Get-FolderBytes $path
    Get-ChildItem $path -Force -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    $freed = $before - (Get-FolderBytes $path)
    Write-Log ('  {0,8:N1} MB  {1}' -f ($freed / 1MB), $path)
    $freed
}

function Invoke-Cleanup {
    $ui.BtnClean.IsEnabled = $false; $total = [int64]0
    Write-Log 'Cleaning...'; Update-UI
    if ($ui.CleanTemp.IsChecked) {
        $total += Clear-Folder $env:TEMP; Update-UI
        $total += Clear-Folder "$env:SystemRoot\Temp"; Update-UI
    }
    if ($ui.CleanShader.IsChecked) {
        foreach ($p in @("$env:LOCALAPPDATA\D3DSCache", "$env:LOCALAPPDATA\NVIDIA\DXCache", "$env:LOCALAPPDATA\NVIDIA\GLCache",
                         "$env:LOCALAPPDATA\AMD\DxCache", "$env:LOCALAPPDATA\AMD\GLCache", "$env:LOCALAPPDATA\AMD\VkCache",
                         "$env:LOCALAPPDATA\Intel\ShaderCache")) { $total += Clear-Folder $p; Update-UI }
    }
    if ($ui.CleanWU.IsChecked) {
        Stop-Service wuauserv -Force -ErrorAction SilentlyContinue
        Stop-Service bits -Force -ErrorAction SilentlyContinue
        $total += Clear-Folder "$env:SystemRoot\SoftwareDistribution\Download"
        Start-Service bits -ErrorAction SilentlyContinue
        Start-Service wuauserv -ErrorAction SilentlyContinue
        Update-UI
    }
    if ($ui.CleanRecycle.IsChecked) { Clear-RecycleBin -Force -ErrorAction SilentlyContinue; Write-Log '  Recycle Bin emptied' }
    if ($ui.CleanDns.IsChecked) { ipconfig /flushdns | Out-Null; Write-Log '  DNS cache flushed' }
    $ui.CleanResult.Text = '{0:N0} MB freed' -f ($total / 1MB)
    Write-Log ('Cleanup finished - {0:N0} MB freed.' -f ($total / 1MB))
    $ui.BtnClean.IsEnabled = $true
}
#endregion

#region ---------- GPU preference ----------
$script:GpuPrefKey = 'HKCU:\Software\Microsoft\DirectX\UserGpuPreferences'

function Set-GpuHighPerf([string]$exe) {
    if (-not (Test-Path $script:GpuPrefKey)) { New-Item -Path $script:GpuPrefKey -Force | Out-Null }
    New-ItemProperty -Path $script:GpuPrefKey -Name $exe -Value 'GpuPreference=2;' -PropertyType String -Force | Out-Null
    Write-Log "High-performance GPU set for $exe"
}

function Show-GpuPrefs {
    $ui.GpuPrefList.Children.Clear()
    if (-not (Test-Path $script:GpuPrefKey)) { return }
    $item = Get-Item $script:GpuPrefKey
    foreach ($name in ($item.GetValueNames() | Where-Object { $_ -ne 'DirectXUserGlobalSettings' })) {
        $val = "$($item.GetValue($name))"
        $mode = if ($val -match 'GpuPreference=2') { 'High performance' } elseif ($val -match 'GpuPreference=1') { 'Power saving' } else { 'Let Windows decide' }
        $row = New-Object Windows.Controls.DockPanel; $row.Margin = '0,4'
        $rm = New-Object Windows.Controls.Button
        $rm.Style = $window.FindResource('Btn'); $rm.Content = 'Remove'; $rm.Padding = '10,4'; $rm.Margin = '10,0,0,0'; $rm.Tag = $name
        $rm.Add_Click({ param($sender) Remove-ItemProperty -Path $script:GpuPrefKey -Name $sender.Tag -ErrorAction SilentlyContinue; Write-Log "Removed GPU preference for $($sender.Tag)"; Show-GpuPrefs })
        [Windows.Controls.DockPanel]::SetDock($rm, 'Right'); $row.Children.Add($rm) | Out-Null
        $modeColor = if ($mode -eq 'High performance') { '#3DF5A0' } else { '#FFB547' }
        $m = New-Text $mode 12 $modeColor 'SemiBold'; $m.Width = 130; $m.VerticalAlignment = 'Center'
        [Windows.Controls.DockPanel]::SetDock($m, 'Right'); $row.Children.Add($m) | Out-Null
        $p = New-Text $name 12 '#DCE3F5'; $p.VerticalAlignment = 'Center'; $p.TextTrimming = 'CharacterEllipsis'; $p.TextWrapping = 'NoWrap'
        $row.Children.Add($p) | Out-Null
        $ui.GpuPrefList.Children.Add($row) | Out-Null
    }
}

function Find-MinecraftJava {
    $roots = @(
        "${env:ProgramFiles(x86)}\Minecraft Launcher\runtime",
        "$env:ProgramFiles\Minecraft Launcher\runtime",
        "$env:LOCALAPPDATA\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime",
        "$env:APPDATA\.minecraft\runtime",
        "$env:APPDATA\PrismLauncher\java",
        "$env:LOCALAPPDATA\Programs\PrismLauncher\java",
        "$env:APPDATA\.tlauncher",
        "$env:APPDATA\CurseForge\minecraft\Install\runtime",
        "$env:USERPROFILE\curseforge\minecraft\Install\runtime",
        "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Zulu", "$env:ProgramFiles\Microsoft"
    )
    $found = foreach ($r in $roots) {
        if ($r -and (Test-Path $r)) { Get-ChildItem $r -Recurse -Filter 'javaw.exe' -ErrorAction SilentlyContinue | ForEach-Object FullName }
    }
    @($found | Select-Object -Unique)
}
#endregion

#region ---------- Minecraft JVM args ----------
function Show-McArgs {
    $ram = $script:Specs.RAMGB
    $alloc = if ($ram -le 6) { 2 } elseif ($ram -le 8) { 3 } elseif ($ram -le 12) { 4 } elseif ($ram -le 16) { 6 } else { 8 }
    $ui.McRamInfo.Text = "You have $ram GB. Recommended: ${alloc} GB for modded / heavy packs (vanilla + Sodium is fine with 2-4 GB)."
    $ui.McArgs.Text = "-Xms${alloc}G -Xmx${alloc}G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 " +
                      "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 " +
                      "-XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 " +
                      "-XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 " +
                      "-XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"
    $ui.McJavaInfo.Text = 'Minecraft runs on javaw.exe. If Windows puts it on the integrated GPU you get a fraction of the FPS. This scans common launchers (official, Prism, CurseForge, TLauncher) and pins every javaw.exe it finds to the high-performance GPU.'
}
#endregion

#region ---------- events ----------
$pages = @{ NavDash = 'PageDash'; NavTweaks = 'PageTweaks'; NavClean = 'PageClean'; NavGpu = 'PageGpu'; NavMc = 'PageMc'; NavLog = 'PageLog' }
foreach ($nav in $pages.Keys) {
    $ui[$nav].Add_Checked({
        param($sender)
        foreach ($k in $pages.Keys) { $ui[$pages[$k]].Visibility = 'Collapsed' }
        $ui[$pages[$sender.Name]].Visibility = 'Visible'
    })
}

function Switch-Maximize { $window.WindowState = if ($window.WindowState -eq 'Maximized') { 'Normal' } else { 'Maximized' } }
$ui.Header.Add_MouseLeftButtonDown({ if ($_.ClickCount -eq 2) { Switch-Maximize } else { $window.DragMove() } })
$ui.BtnClose.Add_Click({ $window.Close() })
$ui.BtnMin.Add_Click({ $window.WindowState = 'Minimized' })
$ui.BtnMax.Add_Click({ Switch-Maximize })

$ui.PresetSafe.Add_Click({ Set-Preset 'S' })
$ui.PresetGaming.Add_Click({ Set-Preset 'G' })
$ui.PresetExtreme.Add_Click({ Set-Preset 'X' })
$ui.PresetCurrent.Add_Click({ Sync-Toggles; Write-Log 'Switches reset to the current system state.' })
$ui.TweakSearch.Add_TextChanged({ Update-TweakFilter })
$ui.BtnApply.Add_Click({ $ui.BtnApply.IsEnabled = $false; try { Invoke-ApplyChanges } catch { Write-Log "Error: $($_.Exception.Message)" } finally { $ui.BtnApply.IsEnabled = $true } })
$ui.BtnRevertAll.Add_Click({ Invoke-RevertAll })

$ui.BtnClean.Add_Click({ Invoke-Cleanup })
$ui.BtnStartup.Add_Click({ Start-Process 'ms-settings:startupapps' })
$ui.BtnStorage.Add_Click({ Start-Process 'ms-settings:storagesense' })
$ui.BtnRestorePt.Add_Click({ [void](New-RestorePoint) })
$ui.BtnWinsock.Add_Click({
    if ([Windows.MessageBox]::Show('Reset Winsock + TCP/IP + DNS? Fixes broken networking; needs a reboot.', 'WaveOptimizer', 'YesNo', 'Question') -eq 'Yes') {
        netsh winsock reset | Out-Null; netsh int ip reset | Out-Null; ipconfig /flushdns | Out-Null
        Write-Log 'Network stack reset - reboot to finish.'
    }
})
$ui.BtnSfc.Add_Click({ Start-Process 'cmd.exe' -ArgumentList '/k sfc /scannow && DISM /Online /Cleanup-Image /RestoreHealth' -Verb RunAs; Write-Log 'SFC + DISM started in a new window.' })

$ui.BtnGfxSettings.Add_Click({ Start-Process 'ms-settings:display-advancedgraphics' })
$ui.BtnAddGame.Add_Click({
    $dlg = New-Object Microsoft.Win32.OpenFileDialog
    $dlg.Filter = 'Programs (*.exe)|*.exe'; $dlg.Multiselect = $true
    if ($dlg.ShowDialog()) { foreach ($f in $dlg.FileNames) { Set-GpuHighPerf $f }; Show-GpuPrefs }
})

$ui.BtnMcGpu.Add_Click({
    Write-Log 'Searching for Minecraft Java installs...'; Update-UI
    $javas = Find-MinecraftJava
    if ($javas.Count -eq 0) {
        Write-Log 'No javaw.exe found. Use GPU & Games -> Add game .exe and pick your launcher''s javaw.exe manually.'
    } else {
        foreach ($j in $javas) { Set-GpuHighPerf $j }
        Write-Log "Pinned $($javas.Count) Java runtime(s) to the high-performance GPU. Restart Minecraft."
    }
    Show-GpuPrefs
})
$ui.BtnModrinth.Add_Click({ Start-Process 'https://modrinth.com/mod/sodium' })
$ui.BtnCopyArgs.Add_Click({ [Windows.Clipboard]::SetText($ui.McArgs.Text); Write-Log 'JVM arguments copied to clipboard.' })
#endregion

#region ---------- self-test (used by the GitHub Actions build) ----------
function Test-Snapshot($t) {
    $snap = @{}
    foreach ($r in @($t.Reg | Where-Object { $_ })) { $snap["$($r.P)|$($r.N)"] = "$(Get-RegValue $r.P $r.N)" }
    foreach ($svc in @($t.Svc | Where-Object { $_ })) { $k = "HKLM:\SYSTEM\CurrentControlSet\Services\$svc"; $snap["$k|Start"] = "$(Get-RegValue $k 'Start')" }
    $snap
}

function Invoke-SelfTest {
    $script:Failures = 0
    Write-Host "== WaveOptimizer $script:Version self-test - Windows build $script:Build, PowerShell $($PSVersionTable.PSVersion) =="
    Write-Host "UI      : XAML loaded, $($ui.Count) named elements"
    try {
        $script:Specs = Get-Specs
        Write-Host ('Specs   : {0} | {1} GB {2} x{3} @ {4} | {5} | GPU: {6}' -f $script:Specs.CPU, $script:Specs.RAMGB, $script:Specs.RAMType,
            $script:Specs.RAMSticks, $script:Specs.RAMSpeed, $script:Specs.DiskKind, (($script:Specs.GPUs | ForEach-Object { $_.Name }) -join ', '))
        Show-Dashboard
        Write-Host "Dash    : $($ui.SpecGrid.Children.Count) spec cards, $($ui.RecList.Children.Count) recommendations"
    } catch { Write-Host "FAIL dashboard: $($_.Exception.Message) (line $($_.InvocationInfo.ScriptLineNumber))"; $script:Failures++ }

    try {
        Build-TweakList
        $active = @($script:Visible | Where-Object { $script:State[$_.Id] }).Count
        Write-Host "Tweaks  : $($script:Tweaks.Count) defined, $($script:Visible.Count) apply on this PC, $active already active"
        Write-Host ('Hidden  : ' + ((@($script:Tweaks | Where-Object { $script:Visible -notcontains $_ }) | ForEach-Object { $_.Id }) -join ', '))
        foreach ($l in 'S', 'G', 'X') {
            Set-Preset $l
            $n = @($script:Visible | Where-Object { [bool]$script:Toggles[$_.Id].IsChecked -ne [bool]$script:State[$_.Id] }).Count
            Write-Host "Preset $l would change $n switch(es)  [button: $($ui.BtnApply.Content)]"
        }
        Sync-Toggles
        $ui.TweakSearch.Text = 'mouse'
        Write-Host "Search  : 'mouse' -> $(@($script:CardById.Values | Where-Object { $_.Visibility -eq 'Visible' }).Count) card(s)"
        $ui.TweakSearch.Text = ''
        $script:CatFilter = 'Services'; Update-TweakFilter
        Write-Host "Filter  : Services -> $(@($script:CardById.Values | Where-Object { $_.Visibility -eq 'Visible' }).Count) card(s)"
        $script:CatFilter = 'All'; Update-TweakFilter
    } catch { Write-Host "FAIL tweak list: $($_.Exception.Message) (line $($_.InvocationInfo.ScriptLineNumber))"; $script:Failures++ }

    try { Show-McArgs; Show-GpuPrefs; Write-Host "MC      : $($ui.McRamInfo.Text)" }
    catch { Write-Host "FAIL minecraft page: $($_.Exception.Message)"; $script:Failures++ }

    if ($RoundTrip) {
        $cands = @($script:Visible | Where-Object { $_.Cat -ne 'Network' -and $_.Id -ne 's_hyperv' -and -not $script:State[$_.Id] })
        Write-Host ''
        Write-Host "== Round-trip: apply + revert $($cands.Count) tweaks that are currently OFF =="
        $pass = 0; $warn = 0
        foreach ($t in $cands) {
            $before = Test-Snapshot $t
            try { Invoke-TweakApply $t } catch { Write-Host "WARN $($t.Id): apply threw: $($_.Exception.Message)"; $warn++ }
            if ($t.Tasks) { $script:AllTasks = @(Get-ScheduledTask) }
            $on = Test-Tweak $t
            try { Invoke-TweakRevert $t } catch { Write-Host "FAIL $($t.Id): revert threw: $($_.Exception.Message)"; $script:Failures++; continue }
            if ($t.Tasks) { $script:AllTasks = @(Get-ScheduledTask) }
            $off = -not (Test-Tweak $t)
            $after = Test-Snapshot $t
            $diff = @($before.Keys | Where-Object { $before[$_] -ne $after[$_] })
            if (-not $off -or $diff.Count) { Write-Host "FAIL $($t.Id): not restored (off=$off, changed: $($diff -join '; '))"; $script:Failures++ }
            elseif (-not $on) { Write-Host "WARN $($t.Id): did not turn on here (edition/hardware)"; $warn++ }
            else { Write-Host "PASS $($t.Id)"; $pass++ }
        }
        $left = @($script:Backup.Keys | Where-Object { $_ -ne 'ultimate' })
        Write-Host "Round-trip: $pass passed, $warn warnings. Backup entries left: $($left.Count) $($left -join ', ')"
        if ($left.Count) { $script:Failures++ }
    }
    Write-Host "== Self-test finished: $script:Failures failure(s) =="
}
#endregion

#region ---------- start ----------
if ($script:Headless) {
    Invoke-SelfTest
    exit ([int]($script:Failures -gt 0))
}

$window.Add_ContentRendered({
    Write-Log 'WaveOptimizer started. Scanning hardware...'; Update-UI
    try { $script:Specs = Get-Specs } catch { Write-Log "Hardware scan error: $($_.Exception.Message)"; $script:Specs = @{ RAMGB = 8; GPUs = @() } }
    try { Show-Dashboard } catch { Write-Log "Dashboard error: $($_.Exception.Message)" }
    Build-TweakList
    Show-McArgs
    Show-GpuPrefs
    Write-Log "Scan complete. Backups are stored in $script:BackupFile"
})

[void]$window.ShowDialog()
#endregion
