// 설정: 붙을 서버, Claude Code 를 돌리는 방식, 음성.
package com.fidenit.kite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fidenit.kite.BuildConfig
import com.fidenit.kite.KiteViewModel
import kotlinx.coroutines.launch

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Hint(text: String) {
    // APERIT Pro 의 화면에는 설명 문장을 두지 않는다 (제목·짧은 버튼·숫자·상태·오류만)
    if (!BuildConfig.PRO) Text(text, color = C.dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
}

/** 적으면 바로 저장되는 칸 */
@Composable
private fun Field(label: String, initial: String, secret: Boolean = false, lines: Int = 1, number: Boolean = false, onChange: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = v,
        onValueChange = {
            v = it
            onChange(it)
        },
        label = { Text(label) },
        singleLine = lines == 1,
        minLines = lines,
        maxLines = if (lines == 1) 1 else 6,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun Choice(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    var sel by remember { mutableStateOf(current) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        for ((value, label) in options) {
            Pill(label, selected = sel == value) {
                sel = value
                onPick(value)
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: KiteViewModel, onBack: () -> Unit) {
    val s = vm.store
    val scope = rememberCoroutineScope()
    var test by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var threshold by remember { mutableStateOf(s.micThreshold.toFloatOrNull() ?: 0.02f) }
    var sureToClear by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(if (BuildConfig.PRO) "api" else s.engineKind) }
    var usage by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("설정", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill("돌아가기", selected = true) {
                vm.resetEngine()
                onBack()
            }
        }

        if (BuildConfig.PRO) {
            Section("연결")
            Text(vm.proState, color = if (vm.proState == "연결됨" || vm.proState == "Connected") C.green else C.text, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            Text("기기 ${vm.proShortId}", color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        } else {
            Section("엔진")
            Hint("폰에서 바로: 이 폰이 Claude API 를 직접 부릅니다. 내 서버: SSH 로 붙은 서버의 Claude Code 를 씁니다(고급).")
            Choice(listOf("api" to "폰에서 바로", "ssh" to "내 서버의 Claude Code"), kind) {
                kind = it
                s.engineKind = it
            }
        }

        if (BuildConfig.PRO) {
            // Pro: 엔진·키·서버 칸이 없다 (서버가 정한다)
        } else if (kind == "api") {
            if (!BuildConfig.PRO) {
                Field("Claude API 키", s.apiKey, secret = true) { s.apiKey = it.trim() }
                Hint("platform.claude.com 에서 발급합니다. 쓴 만큼 Anthropic 에 직접 냅니다. Max·Team 요금제에 들어 있는 월 API 크레딧도 이 키로 쓸 수 있습니다. 키는 이 폰에만 암호화해서 둡니다.")
            }
            Text("모델", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Hint("Opus 가 가장 똑똑하고, Sonnet 은 절반 값, Haiku 는 훨씬 싸고 빠릅니다.")
            Choice(listOf("claude-opus-5-5" to "Opus", "claude-sonnet-5-5" to "Sonnet", "claude-haiku-5-5" to "Haiku"), s.model) { s.model = it }
            Text("생각의 깊이", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Hint("낮을수록 대답이 빨리 나오고 값도 쌉니다. 말로 대화할 때는 낮음이 좋습니다.")
            Choice(listOf("low" to "낮음", "medium" to "보통", "high" to "높음"), s.apiEffort) { s.apiEffort = it }
            if (!BuildConfig.PRO) {
                Spacer(Modifier.height(8.dp))
                Pill(if (testing) "확인 중…" else "키 확인", selected = !testing) {
                    if (!testing) {
                        testing = true
                        test = ""
                        scope.launch {
                            test = vm.testConnection()
                            testing = false
                        }
                    }
                }
                if (test.isNotEmpty()) Text(test, color = if (test.startsWith("실패")) C.red else C.green, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            ServerSettings(vm)
        }

        // Pro 1단계에는 말로 대화하기가 없다: 음성 칸을 통째로 뺀다 (A1)
        if (!BuildConfig.PRO) {
            Section("음성")
            Hint("말로 대화하려면 ElevenLabs 키가 필요합니다.")
            Field("ElevenLabs 키", s.elevenKey, secret = true) { s.elevenKey = it.trim() }
            Field("목소리 ID (비우면 기본 목소리)", s.voiceId) { s.voiceId = it.trim() }
            Text("대화 언어", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Choice(listOf("ko" to "한국어", "en" to "English"), s.lang) { s.lang = it }
            Text("마이크 민감도  ${"%.3f".format(threshold)}" + if (vm.converse) "   지금 들리는 크기 ${"%.3f".format(vm.micLevel)}" else "", color = C.text, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            Hint("이 값보다 큰 소리를 말소리로 봅니다. 조용한데도 듣는 중으로 나오면 올리고, 말을 못 알아채면 내리세요.")
            Slider(value = threshold, onValueChange = {
                threshold = it
                s.micThreshold = "%.3f".format(it)
            }, valueRange = 0.005f..0.1f)
        }

        if (!BuildConfig.PRO) {
            Section("판단 모델 (선택)")
            Hint("주제 고르기, 카드 순서를 빠르게 합니다. 없어도 그대로 동작합니다.")
            Field("TypeSafe Jev 키", s.jevKey, secret = true) { s.jevKey = it.trim() }
        }

        Section("화면")
        Hint("주제 이름을 길게 누르면 그 주제가 보관함으로 내려갑니다.")
        // 되돌릴 수 없으니 한 번 더 눌러야 지운다
        Pill(if (sureToClear) "정말 비우기 (되돌릴 수 없음)" else "화면과 대화 기록 비우기", color = C.red) {
            if (sureToClear) vm.clearAll()
            sureToClear = !sureToClear
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** 내 서버의 Claude Code 를 쓸 때의 설정 (무료 앱의 고급 선택). */
@Composable
private fun ServerSettings(vm: KiteViewModel) {
    val s = vm.store
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var test by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var publicKey by remember { mutableStateOf(s.publicKey) }
    var keyVersion by remember { mutableStateOf(0) }
    Column {
        Section("서버")
        Hint("APERIT 는 이 서버에 SSH 로 붙어서 그 서버의 Claude Code 를 엔진으로 씁니다. 서버의 Claude Code 로그인, CLAUDE.md, 스킬, 훅, MCP 서버, 권한 설정이 그대로 적용됩니다.")
        Field("주소 (IP 또는 도메인)", s.host) { s.host = it.trim() }
        Field("포트", s.port, number = true) { s.port = it.trim() }
        Field("계정", s.user) { s.user = it.trim() }
        Field("암호 (키를 쓰면 비워 둠)", s.password, secret = true) { s.password = it }

        Spacer(Modifier.height(8.dp))
        Text("키로 로그인", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Hint("이 폰만의 키를 만들고, 아래에 나오는 공개 키 한 줄을 서버의 ~/.ssh/authorized_keys 에 추가하세요. 가지고 있는 개인 키를 붙여 넣어도 됩니다.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("키 만들기") {
                scope.launch {
                    publicKey = vm.newKey()
                    keyVersion++
                }
            }
            if (publicKey.isNotEmpty()) Pill("공개 키 복사") { clipboard.setText(AnnotatedString(publicKey)) }
        }
        if (publicKey.isNotEmpty()) Text(publicKey, color = C.dim, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 4, modifier = Modifier.padding(vertical = 6.dp))
        // 키를 새로 만들면 칸의 내용도 바뀌어야 한다
        androidx.compose.runtime.key(keyVersion) {
            Field("개인 키", s.privateKey, lines = 3) {
                s.privateKey = it
                if (publicKey.isNotEmpty()) {
                    publicKey = ""
                    s.publicKey = ""
                }
            }
            Field("키의 암호 (없으면 비워 둠)", s.passphrase, secret = true) { s.passphrase = it }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (testing) "붙는 중…" else "연결 시험", selected = !testing) {
                if (!testing) {
                    testing = true
                    test = ""
                    scope.launch {
                        test = vm.testConnection()
                        testing = false
                    }
                }
            }
            Pill("서버 지문 잊기") {
                s.fingerprint = ""
                vm.resetEngine()
                test = "서버 지문을 잊었습니다. 다음에 붙을 때 새로 기억합니다."
            }
        }
        if (test.isNotEmpty()) Text(test, color = if (test.startsWith("실패")) C.red else C.green, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))

        Section("Claude Code")
        Field("실행 이름 또는 경로", s.claude) { s.claude = it.trim() }
        Field("작업 폴더", s.dir) { s.dir = it.trim() }
        Text("권한", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Hint("기본은 서버의 Claude Code 설정 그대로입니다 (허용해 두지 않은 파일 수정과 명령은 거절됩니다).")
        Choice(listOf("default" to "기본", "acceptEdits" to "파일 수정", "bypassPermissions" to "전부"), s.permission) { s.permission = it }
        Text("생각의 깊이", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Hint("낮을수록 대답이 빨리 나옵니다. 말로 대화할 때는 낮음이 좋습니다.")
        Choice(listOf("low" to "낮음", "medium" to "보통", "high" to "높음"), s.effort) { s.effort = it }
        Field("더 줄 옵션 (예: --model sonnet)", s.extra) { s.extra = it }
    }
}
