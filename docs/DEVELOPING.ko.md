# 개발자용 안내

> 이 문서는 0.3.0 기준의 옛 판이다. 지금 맞는 것은 영어판 [DEVELOPING.md](DEVELOPING.md)다. 0.4.0에서 `src/core.rs`·`src/lang.rs`는 `protocol/rust/src/`로 옮겨졌고, 환경 변수 이름은 `APERIT_...`로 바뀌었다(옛 `LYBOARD_...`도 읽는다).

빌드, 키, 부품을 더하는 법, 코드의 구조. 프로그램이 무엇인지는 [README](../README.ko.md)에 있다.

## 빌드해서 실행

Windows:

```powershell
.\run.ps1          # 빌드해서 실행
.\run.ps1 test     # 테스트
.\run.ps1 pack     # 들고 다닐 수 있는 폴더 portable\APERIT 만들기
```

Mac (Rust는 [rustup](https://rustup.rs)으로 설치, Xcode 명령줄 도구 필요):

```sh
./run.sh            # 빌드해서 실행
./run.sh test       # 테스트
./run.sh pack       # portable/APERIT/APERIT.app 만들기
```

Windows 빌드에는 Rust(`stable-x86_64-pc-windows-gnu`)와 MinGW(WinLibs)가 필요하다. 실행 파일 옆에 `WebView2Loader.dll`이 있어야 뜬다(`run.ps1`이 복사한다).

소개 영상(`KITE-promo.mp4`)을 다시 찍고 편집하는 방법과 녹화용 환경 변수는 [`tools/promo/README.md`](../tools/promo/README.md)에 있다.

## 키

키(Claude, Jev, ElevenLabs, 다른 모델, AG-UI 헤더)는 **설정** 창에서 넣는다. 평문 파일로 남지 않고 앱 안에 암호화해서 보관한다: Windows는 이 PC의 이 계정에서만 열리게(`data/keys.bin`), macOS는 로그인 키체인에. 옛 버전이 남긴 `key.txt`·`jev.txt`·`eleven.txt`·`openai.txt`의 키 줄·`agui.txt`의 헤더 줄은 다음 실행 때 옮겨 담고 지운다(`src/secret.rs`).

| 파일 | 내용 | 없으면 |
|---|---|---|
| `voice.txt` | ElevenLabs 목소리 ID | 기본 목소리 |

`data/`, `vault/`는 저장소에 올라가지 않는다.

## 부품 더하기

화면에 놓이는 카드의 종류(부품)는 `src/core.rs`의 `PARTS` 한 곳에 등록되어 있다. 부품 하나에 관한 것(이름, 기본 크기, 받는 필드와 글자 한도, 항목 형식, 무엇에 쓰는지)이 모두 거기 있고, AI에게 주는 부품 표도 이 목록에서 만들어진다. 지시문(`prompt.md`)의 표를 따로 고치지 않는다.

새 부품을 더하려면 세 군데를 고친다.

1. `src/core.rs`의 `Kind`에 종류를 하나 더한다.
2. 같은 파일의 `PARTS`에 등록한다. `purpose`와 필드 설명은 AI가 부품을 고를 때 읽는 글이다.
3. `src/ui.rs`의 `draw_body`에 그리는 법을 더한다 (컴파일러가 빠진 종류를 알려 준다).

항목에 지켜야 할 형식이 있으면 `core.rs`의 `put_item`에 검사를 더한다. 사용자의 답을 받는 부품이면 `Kind::asks`에 넣는다. 그러면 "내 차례" 자리로 온다.

## 구조

| 파일 | 역할 |
|---|---|
| `src/core.rs` | 보드(분류, 주제, 카드)와 조작, 타입 검사 |
| `src/lang.rs` | 화면 수정 언어: 해석, 직렬화, 스트림을 줄 단위로 끊기 |
| `src/prompt.md` | 모델 지시문 |
| `src/agent.rs` | 한 턴의 작업 루프 (모델 호출 ↔ 도구 실행) |
| `src/tools.rs` | 도구: 명령 실행, 파일, 기억 노트, 브라우저 조작, 웹 |
| `src/decide.rs`, `src/jev.rs` | 메인 모델 앞뒤의 작은 결정 (Jev) |
| `src/ui.rs` | 판 그리기: 배치, 확대·축소, 전환, 부품, 직접 조작 |
| `src/chat.rs` | 왼쪽 대화 구역 |
| `src/vault.rs` | 옵시디언 보관함 (기록, 기억) |
| `src/tts.rs`, `src/eleven.rs`, `src/audio.rs` | 말하기, 받아쓰기, 스피커, 마이크 |
| `src/sys.rs` | 프로그램 폴더, 시각, 창 상단바, 옵시디언 열기 |

## 수정 언어 예

```
say 두 후보를 비교했어요. 골라 주세요.
topic ws 팀 워크숍
card k1 table ws 5x2 장소 비교
set k1 cols 장소 | 이동 시간 | 1인 비용
add k1 r1 강릉 | 2시간 40분 | 18만원
card k2 decision ws 3x2 장소 결정
set k2 text 어디로 할까요?
add k2 o1 강릉 | 멀지만 시설이 좋음
```

## 안드로이드 앱 (`android/`)

폰에서 쓰는 APERIT. 코드 하나에서 앱 두 개가 나온다 (`android/app/build.gradle.kts`의 `productFlavors`).

| 갈래 | 앱 | 엔진 |
|---|---|---|
| `free` | APERIT (`com.fidenit.kite`) | 사용자가 넣은 Claude API 키로 폰이 Claude API 를 직접 부른다. 고급 선택으로 내 서버의 Claude Code(SSH)도 쓸 수 있다 |
| `pro` | APERIT Pro (`com.fidenit.kite.pro`) | 아무것도 넣지 않는다. 같은 요청을 중계 서버로 보내고, 키와 사용량은 서버가 맡는다 |

```sh
cd android
./gradlew assembleFreeDebug                                   # app/build/outputs/apk/free/debug/app-free-debug.apk
./gradlew -PkiteRelayUrl=https://<중계 서버> assembleProDebug # 주소를 안 주면 에뮬레이터에서 이 PC 의 http://10.0.2.2:8787
./gradlew testFreeDebugUnitTest                               # 보드·수정 언어·API 엔진(가짜 서버로 한 턴)·서버 명령 줄·Jev/ElevenLabs 호출(가짜 서버)·앱 시작과 복원(Robolectric, JVM)
```

JDK 17 이상(Android Studio 의 jbr)과 안드로이드 SDK 가 필요하다. `ANDROID_HOME` 이나 `android/local.properties` 의 `sdk.dir=`로 SDK 자리를 알려 준다.

| 파일 (`app/src/main/java/com/fidenit/kite/`) | 역할 |
|---|---|
| `core/Board.kt`, `core/Lang.kt` | 보드와 화면 수정 언어. `src/core.rs`, `src/lang.rs` 를 옮긴 것 |
| `engine/Api.kt` | Claude API 엔진 (공식 Java SDK). 스트리밍, 도구 반복, 1시간 지시문 캐시, 요청마다 사용량과 어림 비용을 더한다. 흐름은 `src/agent.rs` 와 같다 |
| `engine/PhoneTools.kt` | 폰에서 도는 도구: `screen`, 기억 보관함(`note_*`, 앱 저장소의 `vault/`). 웹 검색·읽기는 Anthropic 서버 쪽 도구 |
| `engine/Jev.kt`, `engine/Decide.kt` | 판단 모델: TypeSafe Jev 호출(`src/jev.rs`)과 턴 앞뒤의 작은 결정(`src/decide.rs`). 질문 글과 기준 숫자는 데스크톱과 같다 |
| `engine/Ssh.kt`, `engine/ClaudeCode.kt`, `engine/Detached.kt` | 내 서버의 Claude Code 엔진 (SSH, `claude -p`). 한 턴은 서버에서 떼어 돌고 앱은 그것을 따라 읽는다 (아래) |
| `Journal.kt` | 앱이 꺼져도 남는 파일: 돌고 있는 턴, 줄 세워 둔 말·카드 조작 알림, 서버에서 아직 치우지 못한 실행, 지금 보는 주제 |
| `voice/` | 마이크·스피커, ElevenLabs 받아쓰기와 말하기, 멈춤·기다림 낱말 |
| `KiteViewModel.kt` | 한 턴의 흐름, 소리와 카드의 순서, 말로 대화하기, 엔진 고르기 |
| `ui/` | 화면: 주제, 카드, 설정 |

- **서버의 턴은 떼어 돈다.** 앱은 턴 글을 서버의 `~/.kite/runs/<id>.in` 에 올리고 `setsid nohup` 으로 `claude` 를 그 자리에서 시작한다. 출력은 `<id>.out`, 끝난 코드는 `<id>.exit`, 감싼 bash 의 번호는 `<id>.pid` (`<id>` 는 앱이 만든다: `[A-Za-z0-9-]+`, 셸에 넣기 전에 검사). 앱은 SSH 로 `.out` 을 아직 받지 못한 줄부터 따라 읽는다 (서버가 줄마다 `>` 를 앞에 붙이고, 앱은 줄바꿈으로 끝난 줄만 센다). 연결이 끊기거나 40초 동안 아무것도 오지 않으면 1·2·4·8·15·30초(그 뒤 30초) 간격으로 다시 붙어 받은 줄 다음부터 이어 읽고, 상태 줄에 그렇다고 보인다. 한 실행의 끝은 끝났다는 줄뿐이고 첫 `result` 가 아니다 (`claude` 한 개가 `result` 를 두 번 낼 수 있다). 멈춤은 저장된 프로세스 번호가 이 실행의 감싼 bash 인지 확인한 뒤 그 프로세스 묶음에 TERM (안 되면 KILL) 을 보낸다. 10초쯤 안에 서버에 알리지 못하면 턴은 화면에서 끝나고, 그 실행은 다음에 붙을 때 멈추고 치운다. 끝난 실행의 서버 파일(`.in .out .err .pid`)은 앱이 보드를 저장하고 기록을 지운 뒤에야 지운다. `.exit` 는 남겨 두고(이레 뒤 턴을 올릴 때 지워진다), 이미 끝난 실행에 다시 붙으면 `gone` 이 나와 같은 말이 두 번 돌지 않는다. 멈추거나 치우지 못한 실행 번호는 `toclean.json` 에 합쳐 적고(덮어쓰지 않는다), 서버에서 멈추고 치운 것이 확인된 뒤에야 목록에서 뺀다.
- **앱이 다시 켜져도 남는 것.** SSH 턴은 첫 네트워크 호출 전에 `inflight.json`(턴 글, 사용자가 본 말, 턴 시작 전 보드, 실행 번호)을 쓴다. 앱이 켜질 때 이 파일이 있으면 턴 시작 전 보드로 되돌리고, 그 뒤에 만진 카드를 다시 반영한 다음 0번 줄부터 그 실행을 따라 읽어 결과를 보드에 반영한다 (따라잡는 동안 소리는 내지 않는다). 턴을 처리하고 저장한 뒤 파일을 지운다. API 엔진(과 실행 번호를 받기 전에 끊긴 SSH 턴)은 파일이 있으면 "마지막 말이 끊겼다"고 한 번 알리고 그 말을 보여 줘서 다시 보낼 수 있게 한다. 줄 세워 둔 말과 카드 조작 알림(`queue.json`), 서버에서 아직 못 치운 실행, 지금 보는 주제, 마지막 한마디도 되살아난다. 보드는 카드를 만질 때마다 쓰기 일꾼 하나가 저장하고(파일마다 마지막 상태만 남기고 파일 사이 순서는 지킨다), 앱이 뒤로 갈 때와 화면이 닫힐 때 다 쓸 때까지 기다리므로 앱이 정상적으로 멈추기 전에는 디스크에 있다. 말로 대화하기는 그때 꺼진다. 턴을 저장한 뒤 기록을 지우기 전에 꺼졌다면 다시 붙을 때 주고받은 말을 턴 시작 때의 길이(기록에 적어 둔다)로 되돌리고, 끊긴 동안 만진 카드는 따라잡은 뒤에 순서대로 반영한다.
- 아직 없는 것: 포그라운드 서비스·알림(결과를 받으려면 앱을 열어야 한다), API 엔진 턴 이어 받기, 실제 폰에서의 확인 (엔진은 실제 `sshd`·`claude` 서버로, 앱 쪽은 JVM 시험과 코드 읽기로 확인했다).
- 지시문은 데스크톱과 같은 `src/prompt.md` 를 빌드할 때 복사해 쓰고, 폰에 맞는 덧붙임(`PHONE_NOTE`, `NOTE`)을 뒤에 붙인다. 부품 표는 `core/Board.kt` 의 `PARTS` 에서 만든다: `src/core.rs` 의 `PARTS` 를 고치면 여기도 같이 고친다 (`partsMatchDesktop` 시험이 어긋남을 잡는다).
- API 엔진의 핵심 기억은 `vault/기억/핵심.md` 이고, 데스크톱처럼 따로 정리해 주는 모델이 없어서 모델이 `note_append` 로 직접 적는다.
- 개발용 빌드만 `10.0.2.2`·`localhost` 로의 암호화 없는 연결을 허용한다 (`src/debug/res/xml/network_security_config.xml`).
- **판단 모델(Jev).** 설정에 TypeSafe Jev 키를 넣으면(다른 키처럼 암호화 저장소에 둔다. 비어 있으면 없는 것) 한 턴에 작은 결정 두 가지가 붙는다. 둘 다 `https://api.typesafe.ai/v1/systemone` 한 번 호출이고, 2.5초 안에 끝나지 않으면 버린다 (키 없음, 답 없음, 200이 아닌 상태, 모양이 다른 본문은 모두 "결정 없음": 턴은 Jev 없이 돌던 그대로 가고 화면에는 아무것도 띄우지 않는다).
  - 턴 앞(앱이 다시 켜져 다시 붙는 턴에는 하지 않고, 입력이 있을 때만): 요청의 종류를 상태 줄에, Jev가 고른 주제를 바로 보여 주고, 일·화면 수정·기억 찾기를 시킨 말이면 받았다는 한마디를 먼저 건넨다("네, 바로 해볼게요." 등. 말로 대화 중이면 소리 내어 읽고, 아니면 카드 아래 한마디로 보인다). 턴 글에는 `<already_said>` 가 붙어 모델이 같은 인사를 되풀이하지 않는다. 깊이·종류는 확신 0.5 이상, 주제는 0.6 이상일 때만 따른다.
  - 턴 뒤(뒤에서): 모델이 건드린 카드(최대 20장)마다 화면만 보고 분명한지, 얼마나 중요한지를 묻는다. 분명할 확률이 0.4보다 낮은 카드는 다음 턴 글의 `<review>` 에 한 번 실리고, 중요도(점수/2, 세 단계)는 답을 기다리는 카드 다음에 중요한 카드가 먼저 오도록 순서에 쓰인다.
  - 부를 때마다 앱 파일의 `jev.log` 에 남는다 (시각, 앱 버전, 입력 앞 40자, 고른 값, 걸린 시간. 키는 적지 않는다).
- ElevenLabs: 목소리로 읽다가 실패하면(키 틀림, 한도, 끊김) 그 문장은 폰의 기본 음성으로 읽고 오류는 한 번만 띄운다. 받아쓰기는 HTTP 401·403·429면 바로 끝내고 첫 오류 글을 보여 준다. ElevenLabs 키가 비어 있으면 마이크 권한을 묻지 않고 키 오류를 띄운다.
- 아직 없는 것(Jev): 기억 노트 미리 읽기(SSH 엔진의 노트는 서버에 있다), 겹치는 주제를 합치는 정리 턴(폰에는 쉬는 동안 도는 턴이 없다), 옛 대화 찾기(`<recall>`, 폰에는 턴 기록이 없다), 기다리는 중 들린 말이 비서를 부르는 말인지 판단(Jev가 아니라 Claude Haiku), Jev가 정하는 턴별 생각의 깊이(설정의 깊이를 쓴다).
- 아직 없는 것(음성): 오디오 포커스(전화·음악과 겹칠 때), 폰이 스피커 소리를 자기 말로 듣는지, 마이크를 끝낼 때 화면이 0.5초까지 멈추는 것. 실제 기기에서만 드러난다.
- 확인하지 못한 것: Jev와 ElevenLabs 경로는 가짜 서버로만 돌려 봤다(api.typesafe.ai·api.elevenlabs.io 에는 부르지 않았다). 실제 폰도 에뮬레이터도 쓰지 않았고, 뷰모델은 JVM 에서 Robolectric 으로 돌렸다.
- 아직 없는 것: 경로 찾기, 작업자, 브라우저 도구, 문자·전화, 붙인 사진, Pro 의 Play 구독 연결.

## 중계 서버

APERIT Pro 가 붙는 서버(키 보관, 이용자별 사용량 계량·한도, 구독 확인)는 이 공개 저장소에 두지 않고 별도 비공개 저장소에서 관리한다. 앱 쪽에서 알아야 할 약속만 적는다.

- Anthropic Java SDK 의 `baseUrl` 을 중계 서버 주소로, `apiKey` 를 구독자 토큰으로 둔다. 주소는 빌드할 때 `-PkiteRelayUrl=https://…` 로 넣는다 (저장소에는 적지 않는다).
- 이번 달 한도를 넘으면 HTTP 402. 앱은 "이번 달 사용량을 모두 썼습니다"로 보여 준다.
- `GET /v1/usage` (헤더 `x-api-key: <토큰>`) 로 이번 달 사용량(`used_usd`, `budget_usd`, `plan`)을 묻는다.
