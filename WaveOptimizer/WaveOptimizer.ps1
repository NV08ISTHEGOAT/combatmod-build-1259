<#
    WaveOptimizer - Windows gaming / system optimizer
    -------------------------------------------------
    * Reads your real hardware and tells you what is actually holding your FPS back.
    * Every tweak is a switch: flip it, hit APPLY. Flip it back + APPLY to undo.
    * Every registry value / service it touches is backed up first to
      %ProgramData%\WaveOptimizer\backup.json so "Revert All" restores your original settings.

    Run WaveOptimizer.bat (it elevates to admin for you).
#>

#region ---------- bootstrap: STA + admin ----------
$ErrorActionPreference = 'Stop'

$isAdmin = (New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
$isSta = [Threading.Thread]::CurrentThread.GetApartmentState() -eq 'STA'

if (-not $isAdmin -or -not $isSta) {
    $verb = if ($isAdmin) { 'Open' } else { 'RunAs' }
    Start-Process -FilePath 'powershell.exe' -Verb $verb `
        -ArgumentList "-NoProfile -ExecutionPolicy Bypass -STA -File `"$PSCommandPath`""
    exit
}

Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase

# Hide the console window behind the GUI
Add-Type -Namespace Wave -Name Native -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern IntPtr GetConsoleWindow();
[DllImport("user32.dll")]   public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
'@
[void][Wave.Native]::ShowWindow([Wave.Native]::GetConsoleWindow(), 0)
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
function Get-ActiveSchemeGuid {
    $out = powercfg /getactivescheme
    if ("$out" -match '([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})') { $Matches[1] }
}

function Get-NetInterfaceKeys {
    Get-ChildItem 'HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces' -ErrorAction SilentlyContinue |
        Where-Object {
            $p = Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue
            $p.DhcpIPAddress -or ($p.IPAddress -and "$($p.IPAddress)" -ne '0.0.0.0')
        } |
        ForEach-Object { 'HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\' + $_.PSChildName }
}

function Set-ServiceStart($Name, $StartValue) {
    $path = "HKLM:\SYSTEM\CurrentControlSet\Services\$Name"
    if (-not (Test-Path $path)) { return }
    Set-Reg $path 'Start' $StartValue
    if ($StartValue -eq 4) { Stop-Service -Name $Name -Force -ErrorAction SilentlyContinue }
}

function Restore-ServiceStart($Name) {
    $path = "HKLM:\SYSTEM\CurrentControlSet\Services\$Name"
    Restore-Reg $path 'Start'
    $start = Get-RegValue $path 'Start'
    if ($start -eq 2) { Start-Service -Name $Name -ErrorAction SilentlyContinue }
}

function Get-ProcessorSettingAc($Alias) {
    $out = powercfg /query SCHEME_CURRENT SUB_PROCESSOR $Alias
    $line = $out | Where-Object { $_ -match 'Current AC Power Setting Index' } | Select-Object -First 1
    if ($line -and $line -match '0x([0-9a-fA-F]+)') { [Convert]::ToInt32($Matches[1], 16) }
}
#endregion

#region ---------- TWEAK CATALOGUE ----------
# Reg   = declarative registry changes (applied/reverted/checked automatically)
# Apply / Revert / Check = custom script blocks for things that are not plain registry writes
# Presets: S = Safe, G = Gaming, X = Extreme
$script:Tweaks = @(
    # ---------------- GAMING ----------------
    @{ Id='gamemode'; Cat='Gaming'; Name='Windows Game Mode'; Risk='Safe'; Presets='SGX'
       Desc='Tells Windows to prioritise the game you are playing and pause Windows Update installs while you play.'
       Reg=@( @{P='HKCU:\Software\Microsoft\GameBar'; N='AllowAutoGameMode'; V=1},
              @{P='HKCU:\Software\Microsoft\GameBar'; N='AutoGameModeEnabled'; V=1} ) },

    @{ Id='gamedvr'; Cat='Gaming'; Name='Disable Xbox Game DVR background recording'; Risk='Safe'; Presets='SGX'
       Desc='Stops Windows silently recording gameplay in the background. Frees GPU encoder time and a bit of FPS.'
       Reg=@( @{P='HKCU:\System\GameConfigStore'; N='GameDVR_Enabled'; V=0},
              @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR'; N='AppCaptureEnabled'; V=0},
              @{P='HKLM:\SOFTWARE\Policies\Microsoft\Windows\GameDVR'; N='AllowGameDVR'; V=0} ) },

    @{ Id='hags'; Cat='Gaming'; Name='Hardware-accelerated GPU scheduling'; Risk='Safe'; Presets='GX'; Reboot=$true
       Desc='Lets the GPU manage its own memory queue. Lower latency on GTX 10-series / RX 5000 and newer. Needs a reboot.'
       Reg=@( @{P='HKLM:\SYSTEM\CurrentControlSet\Control\GraphicsDrivers'; N='HwSchMode'; V=2} ) },

    @{ Id='gamesprio'; Cat='Gaming'; Name='Boost game CPU/GPU scheduling priority'; Risk='Safe'; Presets='GX'
       Desc='Raises the Multimedia Class Scheduler "Games" profile: higher GPU priority, high scheduling category.'
       Reg=@( @{P='HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile\Tasks\Games'; N='GPU Priority'; V=8},
              @{P='HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile\Tasks\Games'; N='Priority'; V=6},
              @{P='HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile\Tasks\Games'; N='Scheduling Category'; V='High'; T='String'},
              @{P='HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile\Tasks\Games'; N='SFIO Priority'; V='High'; T='String'},
              @{P='HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile'; N='SystemResponsiveness'; V=10} ) },

    @{ Id='foreground'; Cat='Gaming'; Name='Favor foreground app (short CPU quantum)'; Risk='Safe'; Presets='GX'
       Desc='Win32PrioritySeparation = 0x26: the window you are focused on gets longer, more frequent CPU slices.'
       Reg=@( @{P='HKLM:\SYSTEM\CurrentControlSet\Control\PriorityControl'; N='Win32PrioritySeparation'; V=38} ) },

    @{ Id='mouseaccel'; Cat='Gaming'; Name='Disable mouse acceleration'; Risk='Safe'; Presets='GX'
       Desc='Turns off "Enhance pointer precision" so aim is 1:1 with your hand movement.'
       Reg=@( @{P='HKCU:\Control Panel\Mouse'; N='MouseSpeed'; V='0'; T='String'},
              @{P='HKCU:\Control Panel\Mouse'; N='MouseThreshold1'; V='0'; T='String'},
              @{P='HKCU:\Control Panel\Mouse'; N='MouseThreshold2'; V='0'; T='String'} ) },

    # ---------------- POWER ----------------
    @{ Id='powerplan'; Cat='Power & CPU'; Name='Ultimate Performance power plan'; Risk='Safe'; Presets='GX'
       Desc='Unlocks and activates the hidden Ultimate Performance plan (falls back to High Performance). CPU stops downclocking between frames.'
       Apply={
           $before = Get-ActiveSchemeGuid
           if (-not $script:Backup.ContainsKey('powerplan')) { $script:Backup['powerplan'] = @{ Existed=$true; Value=$before; Type='Scheme' }; Save-Backup }
           $existing = powercfg /list | Where-Object { $_ -match 'Ultimate Performance' } | Select-Object -First 1
           $guid = $null
           if ($existing -and "$existing" -match '([0-9a-fA-F-]{36})') { $guid = $Matches[1] }
           if (-not $guid) {
               $dup = powercfg -duplicatescheme e9a42b02-d5df-448d-aa00-03f14749eb61
               if ("$dup" -match '([0-9a-fA-F-]{36})') { $guid = $Matches[1] }
           }
           if (-not $guid) { $guid = '8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c'; Write-Log 'Ultimate plan not available on this PC - using High Performance.' }
           powercfg /setactive $guid | Out-Null
       }
       Revert={
           if ($script:Backup.ContainsKey('powerplan')) {
               powercfg /setactive $script:Backup['powerplan'].Value | Out-Null
               $script:Backup.Remove('powerplan'); Save-Backup
           } else { powercfg /setactive 381b4222-f694-41f0-9685-ff5bb260df2e | Out-Null }
       }
       Check={ $n = "$(powercfg /getactivescheme)"; $n -match 'Ultimate|High performance|Hohe Leistung|Hoge prestaties' } },

    @{ Id='powerthrottle'; Cat='Power & CPU'; Name='Disable power throttling'; Risk='Moderate'; Presets='GX'
       Desc='Stops Windows parking background/game threads on slow efficiency states. Uses more battery on laptops.'
       Reg=@( @{P='HKLM:\SYSTEM\CurrentControlSet\Control\Power\PowerThrottling'; N='PowerThrottlingOff'; V=1} ) },

    @{ Id='coreparking'; Cat='Power & CPU'; Name='Disable CPU core parking (current plan)'; Risk='Moderate'; Presets='X'
       Desc='Keeps 100% of cores unparked on AC power. Can smooth out stutter on older Intel/AMD CPUs.'
       Apply={
           if (-not $script:Backup.ContainsKey('cpmincores')) {
               $cur = Get-ProcessorSettingAc 'CPMINCORES'; if ($null -eq $cur) { $cur = 5 }
               $script:Backup['cpmincores'] = @{ Existed=$true; Value=$cur; Type='Power' }; Save-Backup
           }
           powercfg -setacvalueindex SCHEME_CURRENT SUB_PROCESSOR CPMINCORES 100 | Out-Null
           powercfg /setactive SCHEME_CURRENT | Out-Null
       }
       Revert={
           $v = 5; if ($script:Backup.ContainsKey('cpmincores')) { $v = [int]$script:Backup['cpmincores'].Value; $script:Backup.Remove('cpmincores'); Save-Backup }
           powercfg -setacvalueindex SCHEME_CURRENT SUB_PROCESSOR CPMINCORES $v | Out-Null
           powercfg /setactive SCHEME_CURRENT | Out-Null
       }
       Check={ (Get-ProcessorSettingAc 'CPMINCORES') -eq 100 } },

    @{ Id='usbsuspend'; Cat='Power & CPU'; Name='Disable USB selective suspend'; Risk='Safe'; Presets='GX'
       Desc='Stops Windows putting your mouse/keyboard/headset USB ports to sleep (fixes random input lag spikes).'
       Apply={ powercfg -setacvalueindex SCHEME_CURRENT 2a737441-1930-4402-8d77-b2bebba308a3 48e6b7a6-50f5-4782-a5d4-53bb8f07e226 0 | Out-Null; powercfg /setactive SCHEME_CURRENT | Out-Null }
       Revert={ powercfg -setacvalueindex SCHEME_CURRENT 2a737441-1930-4402-8d77-b2bebba308a3 48e6b7a6-50f5-4782-a5d4-53bb8f07e226 1 | Out-Null; powercfg /setactive SCHEME_CURRENT | Out-Null }
       Check={
           $out = powercfg /query SCHEME_CURRENT 2a737441-1930-4402-8d77-b2bebba308a3 48e6b7a6-50f5-4782-a5d4-53bb8f07e226
           $line = $out | Where-Object { $_ -match 'Current AC Power Setting Index' } | Select-Object -First 1
           $line -match '0x00000000'
       } },

    # ---------------- VISUALS ----------------
    @{ Id='visualfx'; Cat='Windows Visuals'; Name='Visual effects: best performance'; Risk='Safe'; Presets='GX'
       Desc='Kills window animations, fades and taskbar animations. Desktop feels snappier, frees a little GPU/CPU.'
       Reg=@( @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\VisualEffects'; N='VisualFXSetting'; V=2},
              @{P='HKCU:\Control Panel\Desktop\WindowMetrics'; N='MinAnimate'; V='0'; T='String'},
              @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced'; N='TaskbarAnimations'; V=0} ) },

    @{ Id='transparency'; Cat='Windows Visuals'; Name='Disable transparency effects'; Risk='Safe'; Presets='GX'
       Desc='Removes the blur/acrylic effect on taskbar and Start. Saves GPU work, especially on integrated graphics.'
       Reg=@( @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize'; N='EnableTransparency'; V=0} ) },

    @{ Id='menudelay'; Cat='Windows Visuals'; Name='Instant menus + no startup delay'; Risk='Safe'; Presets='SGX'
       Desc='Menus open instantly (MenuShowDelay 0) and startup apps launch without the artificial 10 s delay.'
       Reg=@( @{P='HKCU:\Control Panel\Desktop'; N='MenuShowDelay'; V='0'; T='String'},
              @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Serialize'; N='StartupDelayInMSec'; V=0} ) },

    # ---------------- BACKGROUND / PRIVACY ----------------
    @{ Id='bgapps'; Cat='Background & Privacy'; Name='Block background Store apps'; Risk='Safe'; Presets='SGX'
       Desc='Stops Microsoft Store apps running in the background when you are not using them.'
       Reg=@( @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\BackgroundAccessApplications'; N='GlobalUserDisabled'; V=1},
              @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\Search'; N='BackgroundAppGlobalToggle'; V=0} ) },

    @{ Id='telemetry'; Cat='Background & Privacy'; Name='Disable telemetry + tracking services'; Risk='Safe'; Presets='SGX'
       Desc='Turns off DiagTrack (Connected User Experiences) and the WAP push service that feed data to Microsoft.'
       Apply={
           Set-Reg 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\DataCollection' 'AllowTelemetry' 0
           Set-ServiceStart 'DiagTrack' 4; Set-ServiceStart 'dmwappushservice' 4
       }
       Revert={
           Restore-Reg 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\DataCollection' 'AllowTelemetry'
           Restore-ServiceStart 'DiagTrack'; Restore-ServiceStart 'dmwappushservice'
       }
       Check={ (Get-RegValue 'HKLM:\SYSTEM\CurrentControlSet\Services\DiagTrack' 'Start') -eq 4 } },

    @{ Id='ads'; Cat='Background & Privacy'; Name='Disable ads, tips and suggested apps'; Risk='Safe'; Presets='SGX'
       Desc='No more Start menu ads, "tips", lock-screen spam or silently installed suggested apps.'
       Reg=@( @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager'; N='SilentInstalledAppsEnabled'; V=0},
              @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager'; N='SystemPaneSuggestionsEnabled'; V=0},
              @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager'; N='SubscribedContent-338388Enabled'; V=0},
              @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager'; N='SubscribedContent-338389Enabled'; V=0},
              @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\ContentDeliveryManager'; N='SoftLandingEnabled'; V=0},
              @{P='HKCU:\Software\Microsoft\Windows\CurrentVersion\AdvertisingInfo'; N='Enabled'; V=0} ) },

    @{ Id='sysmain'; Cat='Background & Privacy'; Name='Disable SysMain (Superfetch)'; Risk='Moderate'; Presets='X'
       Desc='Stops RAM preloading + disk churn. Helps on HDDs and low-RAM PCs; on a fast SSD with 16 GB+ leave it ON.'
       Apply={ Set-ServiceStart 'SysMain' 4 }
       Revert={ Restore-ServiceStart 'SysMain' }
       Check={ (Get-RegValue 'HKLM:\SYSTEM\CurrentControlSet\Services\SysMain' 'Start') -eq 4 } },

    @{ Id='wsearch'; Cat='Background & Privacy'; Name='Disable Windows Search indexer'; Risk='Moderate'; Presets='X'
       Desc='Stops background file indexing. Start-menu file search becomes slower in exchange.'
       Apply={ Set-ServiceStart 'WSearch' 4 }
       Revert={ Restore-ServiceStart 'WSearch' }
       Check={ (Get-RegValue 'HKLM:\SYSTEM\CurrentControlSet\Services\WSearch' 'Start') -eq 4 } },

    # ---------------- NETWORK ----------------
    @{ Id='netthrottle'; Cat='Network'; Name='Disable network throttling'; Risk='Safe'; Presets='GX'
       Desc='Removes the packet-rate cap Windows applies while multimedia is playing (NetworkThrottlingIndex).'
       Reg=@( @{P='HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Multimedia\SystemProfile'; N='NetworkThrottlingIndex'; V=-1} ) },

    @{ Id='nagle'; Cat='Network'; Name="Disable Nagle's algorithm (lower ping)"; Risk='Safe'; Presets='GX'
       Desc='Sends small game packets immediately instead of bundling them. Can shave a few ms of input-to-server delay.'
       Apply={ foreach ($k in Get-NetInterfaceKeys) { Set-Reg $k 'TcpAckFrequency' 1; Set-Reg $k 'TCPNoDelay' 1 } }
       Revert={ foreach ($k in Get-NetInterfaceKeys) { Restore-Reg $k 'TcpAckFrequency'; Restore-Reg $k 'TCPNoDelay' } }
       Check={ $ks = @(Get-NetInterfaceKeys); $ks.Count -gt 0 -and -not ($ks | Where-Object { (Get-RegValue $_ 'TCPNoDelay') -ne 1 }) } }
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
        <StackPanel Orientation="Horizontal" VerticalAlignment="Top" Margin="26,18,0,0">
          <TextBlock Text="WAVE" FontSize="34" FontWeight="Black" Foreground="{StaticResource WaveGrad}"/>
          <TextBlock Text="OPTIMIZER" FontSize="34" FontWeight="Light" Margin="8,0,0,0" Foreground="White"/>
          <Border Background="#1A00E5FF" BorderBrush="#5500E5FF" BorderThickness="1" CornerRadius="8" Margin="14,10,0,10" Padding="8,2">
            <TextBlock Text="v1.0" FontSize="12" Foreground="#00E5FF" VerticalAlignment="Center"/>
          </Border>
        </StackPanel>
        <TextBlock x:Name="HeaderSub" VerticalAlignment="Top" Margin="28,64,0,0" FontSize="13" Foreground="#9AA6C4" Text="Scanning hardware..."/>
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
          <Border DockPanel.Dock="Top" Background="#0F1626" BorderBrush="#1C2740" BorderThickness="1" CornerRadius="12" Padding="14" Margin="0,0,0,12">
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
                <CheckBox x:Name="ChkRestore" Content="Create restore point first" IsChecked="True" VerticalAlignment="Center" Margin="8,0,0,0"/>
              </StackPanel>
            </DockPanel>
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
    if ($s.PowerPlan -notmatch 'Ultimate|High') {
        Add-Rec 'mid' "Power plan: $($s.PowerPlan)" 'Turn on "Ultimate Performance power plan" in Tweaks so the CPU stops downclocking.'; $recs++
    }
    if ($recs -eq 0) { Add-Rec 'ok' 'No hardware bottlenecks detected' 'Your setup looks well configured. Apply the Gaming preset in Tweaks for the last few percent.' }
}
#endregion

#region ---------- tweak engine ----------
function Test-Tweak($t) {
    try {
        if ($t.Reg) {
            foreach ($r in $t.Reg) { if ("$(Get-RegValue $r.P $r.N)" -ne "$($r.V)") { return $false } }
            return $true
        }
        return [bool](& $t.Check)
    } catch { return $false }
}

function Invoke-TweakApply($t) {
    if ($t.Reg) { foreach ($r in $t.Reg) { $type = if ($r.T) { $r.T } else { 'DWord' }; Set-Reg $r.P $r.N $r.V $type } }
    else { & $t.Apply }
}

function Invoke-TweakRevert($t) {
    if ($t.Reg) { foreach ($r in $t.Reg) { Restore-Reg $r.P $r.N } }
    else { & $t.Revert }
}

$script:Toggles = @{}
function Build-TweakList {
    $ui.TweakList.Children.Clear()
    foreach ($cat in ($script:Tweaks | ForEach-Object { $_.Cat } | Select-Object -Unique)) {
        $h = New-Text $cat.ToUpper() 12 '#7F8BA8' 'Bold'; $h.Margin = '2,10,0,8'
        $ui.TweakList.Children.Add($h) | Out-Null
        foreach ($t in ($script:Tweaks | Where-Object { $_.Cat -eq $cat })) {
            $card = New-Card; $card.Margin = '0,0,0,8'
            $dp = New-Object Windows.Controls.DockPanel
            $tg = New-Object Windows.Controls.Primitives.ToggleButton
            $tg.Style = $window.FindResource('Switch'); $tg.VerticalAlignment = 'Center'; $tg.Margin = '0,0,16,0'
            $tg.IsChecked = Test-Tweak $t
            [Windows.Controls.DockPanel]::SetDock($tg, 'Left')
            $dp.Children.Add($tg) | Out-Null

            $riskColor = if ($t.Risk -eq 'Safe') { '#3DF5A0' } else { '#FFB547' }
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
        }
    }
    Update-Count
}

function Update-Count {
    $on = @($script:Tweaks | Where-Object { Test-Tweak $_ }).Count
    $all = $script:Tweaks.Count
    $ui.SideCount.Text = "$on / $all"
    $ui.SideBar.Value = [math]::Round(100 * $on / $all)
}

function Set-Preset([string]$letter) {
    foreach ($t in $script:Tweaks) { $script:Toggles[$t.Id].IsChecked = $t.Presets.Contains($letter) }
    Write-Log "Preset selected - review the switches, then press APPLY CHANGES."
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
    $todo = @($script:Tweaks | Where-Object { [bool]$script:Toggles[$_.Id].IsChecked -ne (Test-Tweak $_) })
    if ($todo.Count -eq 0) { Write-Log 'Nothing to change - every switch already matches your system.'; return }

    if ($ui.ChkRestore.IsChecked) {
        if (-not (New-RestorePoint)) {
            $r = [Windows.MessageBox]::Show('Could not create a restore point. Continue anyway? (Every change is still backed up and revertible.)', 'WaveOptimizer', 'YesNo', 'Warning')
            if ($r -ne 'Yes') { return }
        }
    }

    $reboot = $false
    foreach ($t in $todo) {
        $want = [bool]$script:Toggles[$t.Id].IsChecked
        try {
            if ($want) { Invoke-TweakApply $t; Write-Log "ON   $($t.Name)" }
            else       { Invoke-TweakRevert $t; Write-Log "OFF  $($t.Name) (restored original)" }
            if ($t.Reboot) { $reboot = $true }
        } catch { Write-Log "FAIL $($t.Name): $($_.Exception.Message)" }
        Update-UI
    }
    foreach ($t in $script:Tweaks) { $script:Toggles[$t.Id].IsChecked = Test-Tweak $t }
    Update-Count
    $script:Specs.PowerPlan = if ("$(powercfg /getactivescheme)" -match '\((.+)\)') { $Matches[1] } else { '?' }
    Show-Dashboard
    $msg = "Done - $($todo.Count) change(s) applied."
    if ($reboot) { $msg += ' Some changes need a reboot.' } else { $msg += ' Sign out/in or reboot for visual tweaks to fully apply.' }
    Write-Log $msg
    [Windows.MessageBox]::Show($msg, 'WaveOptimizer', 'OK', 'Information') | Out-Null
}

function Invoke-RevertAll {
    $r = [Windows.MessageBox]::Show('Restore every setting WaveOptimizer changed back to its original value?', 'WaveOptimizer', 'YesNo', 'Question')
    if ($r -ne 'Yes') { return }
    foreach ($t in $script:Tweaks) { try { Invoke-TweakRevert $t } catch { Write-Log "FAIL revert $($t.Name): $($_.Exception.Message)" } }
    # anything left over in the backup (e.g. interfaces) gets restored too
    foreach ($k in @($script:Backup.Keys)) {
        if ($k -like '*|*') { $parts = $k -split '\|', 2; try { Restore-Reg $parts[0] $parts[1] } catch { } }
    }
    foreach ($t in $script:Tweaks) { $script:Toggles[$t.Id].IsChecked = Test-Tweak $t }
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
    foreach ($name in $item.GetValueNames()) {
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
$ui.PresetCurrent.Add_Click({ foreach ($t in $script:Tweaks) { $script:Toggles[$t.Id].IsChecked = Test-Tweak $t }; Write-Log 'Switches reset to current system state.' })
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

#region ---------- start ----------
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
