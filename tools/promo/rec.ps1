# 소개 영상 한 토막을 녹화한다: 시험용 KITE 를 화면 밖에 띄워 자동 재생 대본을 돌리고, 프레임마다 찍어 MP4 + 위치 기록(.jsonl)을 만든다.
# 창을 앞으로 가져오지 않고 키도 보내지 않는다. 할 일이 다 끝나면 프로그램이 스스로 녹화를 마친다.
#   .\rec.ps1 -Name a -Autoplay "입력|@click 1 2" [-Demo scenes.txt] [-LineMs 200] [-Seed ops.log] [-Promo intro|outro] [-Theme light|dark]
param(
    [Parameter(Mandatory)] [string] $Name,
    [string] $Autoplay = " ",
    [string] $Demo = "",
    [int] $LineMs = 0,
    [string] $Seed = "",
    [string] $Routes = "",
    [string] $Promo = "",
    [int] $Max = 240,
    [int] $Tail = 2500,
    [string] $Size = "2160,1215",
    [string] $Zoom = "1.35",
    [int] $Fps = 60,
    [ValidateSet("light", "dark")] [string] $Theme = "light"
)
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$here = $PSScriptRoot
$base = Join-Path $here "run-$Name"
$out = Join-Path $here "$Name.mp4"
Get-Process kite -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$base\*" } | Stop-Process -Force
Start-Sleep -Milliseconds 300
Remove-Item $base -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$base\data" | Out-Null
Copy-Item "$root\target\release\kite.exe", "$root\target\release\WebView2Loader.dll" $base -Force
Set-Content "$base\data\lang.txt" "en" -NoNewline
Set-Content "$base\data\theme.txt" $Theme -NoNewline
if ($Seed) { Copy-Item $Seed "$base\data\ops.log" -Force }
if ($Routes) { Copy-Item $Routes "$base\data\routes" -Recurse -Force }
Remove-Item $out, "$out.done", "$out.jsonl" -ErrorAction SilentlyContinue
$vars = @{
    LYBOARD_VIDEO      = $out
    LYBOARD_VIDEO_FPS  = "$Fps"
    LYBOARD_VIDEO_MAX  = "$Max"
    LYBOARD_VIDEO_TAIL = "$Tail"
    LYBOARD_AUTOPLAY   = $Autoplay
    LYBOARD_MUTE       = '1'
    LYBOARD_POS        = '-4000,-3000'
    LYBOARD_SIZE       = $Size
    LYBOARD_SCALE      = $Zoom
    LYBOARD_STALE_DAYS = '9999'
    ANTHROPIC_API_KEY  = 'sk-ant-test-invalid'
    LYBOARD_ENGINE     = 'api'
}
if ($Demo) { $vars.LYBOARD_DEMO = '1'; $vars.LYBOARD_DEMO_FILE = $Demo }
if ($LineMs) { $vars.LYBOARD_DEMO_LINE_MS = "$LineMs" }
if ($Promo) { $vars.LYBOARD_PROMO = "$here\assets\fidenit-symbol.png"; $vars.LYBOARD_PROMO_MODE = $Promo }
$saved = @{}
foreach ($k in $vars.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $vars[$k]) }
$p = Start-Process -FilePath "$base\kite.exe" -WorkingDirectory $base -PassThru
foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
$deadline = (Get-Date).AddSeconds($Max + 30)
while (-not (Test-Path "$out.done") -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
Start-Sleep -Milliseconds 300
$p | Stop-Process -Force -ErrorAction SilentlyContinue
if (Test-Path "$out.done") {
    $frames = Get-Content "$out.done"
    $lines = (Get-Content "$out.jsonl" | Measure-Object -Line).Lines
    "{0}: {1:N1} MB, {2} video frames, {3} captured" -f "$Name.mp4", ((Get-Item $out).Length / 1MB), $frames, $lines
} else { "녹화되지 않음 ($Name)" }
