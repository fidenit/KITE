# 소개 영상의 네 토막을 차례로 녹화한다 (2160x1215, 화면 배율 1.35 = 논리 1600x900, 초당 60장, 영어, 어두운 화면).
#   a: 흐름 한 번 (여는 장면 포함)   b: 내용에 맞는 카드   e: 긴 프로젝트의 할 일·컨펌   d: 자유로운 화면 조작 (닫는 장면 포함)
param([string[]]$Only = @())
$pr = $PSScriptRoot
$g = "$pr\assets"
function want($n) { $Only.Count -eq 0 -or $Only -contains $n }
$input_box = '@click 231 715'

if (want 'a') {
    & "$pr\rec.ps1" -Name a -Promo intro -Demo "$g\promo_scenes.txt" -Routes "$g\promo_routes" -LineMs 90 `
        -Autoplay "~0.3|#ask|$input_box|>Plan our team workshop in Gangneung next Friday.|#built|~1.6|@move 236 188|~0.5|#answer|@click 231 182|#done|~2.2"
}
if (want 'b') {
    & "$pr\rec.ps1" -Name b -Demo "$pr\scenes_b.txt" -LineMs 260 `
        -Autoplay "~0.8|#ask|$input_box|>Newsletter launches Dec 1. Goal 5,000 subscribers, 1,240 now.|#built|~2.0"
}
if (want 'e') {
    $env:LYBOARD_DEMO_SEED = "$pr\seed_e.ops"
    & "$pr\rec.ps1" -Name e -Demo "$pr\scenes_e.txt" -LineMs 110 `
        -Autoplay "~2.2|#ask|$input_box|>Morning. What changed overnight?|#queued|~2.2|@move 236 188|~0.4|#answer1|@click 230 181|#next1|~2.0|@move 236 188|~0.3|#answer2|@click 230 181|#next2|~2.4"
    Remove-Item Env:LYBOARD_DEMO_SEED
}
if (want 'd') {
    & "$pr\rec.ps1" -Name d -Promo outro -Seed "$g\seed_groups.ops" `
        -Autoplay "~1.2|#pan|@drag 1350 700 -850 0 60|~0.5|@drag 900 760 850 -400 60|~0.8|#pick|@click 650 405|~2.2|#close|@click 393 52|~1.8|#in|@wheel 1326 300 10|~1.6|@wheel 800 620 10|~2.0|#out|@wheel 1000 450 -10|~1.5|@wheel 1000 450 -10|~1.0|#end"
}
foreach ($n in 'a', 'b', 'e', 'd') {
    if ((want $n) -and (Test-Path "$pr\$n.mp4.jsonl")) {
        $marks = Get-Content "$pr\$n.mp4.jsonl" | Where-Object { $_ -match '"mark"' } | ForEach-Object { $j = $_ | ConvertFrom-Json; "{0}@{1:N2}" -f $j.mark, $j.t }
        "$n marks: $($marks -join '  ')"
    }
}
