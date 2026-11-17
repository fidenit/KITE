# 개발자용 안내

빌드, 키, 부품을 더하는 법, 코드의 구조. 프로그램이 무엇인지는 [README](../README.ko.md)에 있다.

## 빌드해서 실행

Windows:

```powershell
.\run.ps1          # 빌드해서 실행
.\run.ps1 test     # 테스트
.\run.ps1 pack     # 들고 다닐 수 있는 폴더 portable\KITE 만들기
```

Mac (Rust는 [rustup](https://rustup.rs)으로 설치, Xcode 명령줄 도구 필요):

```sh
./run.sh            # 빌드해서 실행
./run.sh test       # 테스트
./run.sh pack       # portable/KITE/KITE.app 만들기
```

Windows 빌드에는 Rust(`stable-x86_64-pc-windows-gnu`)와 MinGW(WinLibs)가 필요하다. 실행 파일 옆에 `WebView2Loader.dll`이 있어야 뜬다(`run.ps1`이 복사한다).

소개 영상(`KITE-promo.mp4`)을 다시 찍고 편집하는 방법과 녹화용 환경 변수는 [`tools/promo/README.md`](../tools/promo/README.md)에 있다.

## 키

프로그램 폴더(실행 파일이 있는 폴더)에 텍스트 파일로 둔다. 처음 실행하면 왼쪽 구역에서 입력할 수도 있다.

| 파일 | 내용 | 없으면 |
|---|---|---|
| `key.txt` | Claude API 키 | 시작할 수 없음 (필수) |
| `jev.txt` | TypeSafe Jev 키 | 판단 모델 없이 동작 |
| `eleven.txt` | ElevenLabs 키 | OS 기본 음성으로 읽고, 마이크 대화는 못 씀 |
| `voice.txt` | ElevenLabs 목소리 ID | 기본 목소리 |

키 파일과 `data/`, `vault/`는 저장소에 올라가지 않는다.

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

폰에서 쓰는 KITE. 코드 하나에서 앱 두 개가 나온다 (`android/app/build.gradle.kts`의 `productFlavors`).

| 갈래 | 앱 | 엔진 |
|---|---|---|
| `free` | KITE (`com.fidenit.kite`) | 사용자가 넣은 Claude API 키로 폰이 Claude API 를 직접 부른다. 고급 선택으로 내 서버의 Claude Code(SSH)도 쓸 수 있다 |
| `pro` | KITE Pro (`com.fidenit.kite.pro`) | 아무것도 넣지 않는다. 같은 요청을 중계 서버로 보내고, 키와 사용량은 서버가 맡는다 |

```sh
cd android
./gradlew assembleFreeDebug                                   # app/build/outputs/apk/free/debug/app-free-debug.apk
./gradlew -PkiteRelayUrl=https://<중계 서버> assembleProDebug # 주소를 안 주면 에뮬레이터에서 이 PC 의 http://10.0.2.2:8787
./gradlew testFreeDebugUnitTest                               # 보드·수정 언어·API 엔진(가짜 서버로 한 턴)·서버 명령 줄
```

JDK 17 이상(Android Studio 의 jbr)과 안드로이드 SDK 가 필요하다. `ANDROID_HOME` 이나 `android/local.properties` 의 `sdk.dir=`로 SDK 자리를 알려 준다.

| 파일 (`app/src/main/java/com/fidenit/kite/`) | 역할 |
|---|---|
| `core/Board.kt`, `core/Lang.kt` | 보드와 화면 수정 언어. `src/core.rs`, `src/lang.rs` 를 옮긴 것 |
| `engine/Api.kt` | Claude API 엔진 (공식 Java SDK). 스트리밍, 도구 반복, 1시간 지시문 캐시, 요청마다 사용량과 어림 비용을 더한다. 흐름은 `src/agent.rs` 와 같다 |
| `engine/PhoneTools.kt` | 폰에서 도는 도구: `screen`, 기억 보관함(`note_*`, 앱 저장소의 `vault/`). 웹 검색·읽기는 Anthropic 서버 쪽 도구 |
| `engine/Ssh.kt`, `engine/ClaudeCode.kt` | 내 서버의 Claude Code 엔진 (SSH, `claude -p`) |
| `voice/` | 마이크·스피커, ElevenLabs 받아쓰기와 말하기, 멈춤·기다림 낱말 |
| `KiteViewModel.kt` | 한 턴의 흐름, 소리와 카드의 순서, 말로 대화하기, 엔진 고르기 |
| `ui/` | 화면: 주제, 카드, 설정 |

- 지시문은 데스크톱과 같은 `src/prompt.md` 를 빌드할 때 복사해 쓰고, 폰에 맞는 덧붙임(`PHONE_NOTE`, `NOTE`)을 뒤에 붙인다. 부품 표는 `core/Board.kt` 의 `PARTS` 에서 만든다: `src/core.rs` 의 `PARTS` 를 고치면 여기도 같이 고친다 (`partsMatchDesktop` 시험이 어긋남을 잡는다).
- API 엔진의 핵심 기억은 `vault/기억/핵심.md` 이고, 데스크톱처럼 따로 정리해 주는 모델이 없어서 모델이 `note_append` 로 직접 적는다.
- 개발용 빌드만 `10.0.2.2`·`localhost` 로의 암호화 없는 연결을 허용한다 (`src/debug/res/xml/network_security_config.xml`).
- 아직 없는 것: 판단 모델(Jev) 연동, 경로 찾기, 작업자, 브라우저 도구, 문자·전화, 옛 대화 찾기(`<recall>`), 붙인 사진, Pro 의 Play 구독 연결.

## 중계 서버

KITE Pro 가 붙는 서버(키 보관, 이용자별 사용량 계량·한도, 구독 확인)는 이 공개 저장소에 두지 않고 별도 비공개 저장소에서 관리한다. 앱 쪽에서 알아야 할 약속만 적는다.

- Anthropic Java SDK 의 `baseUrl` 을 중계 서버 주소로, `apiKey` 를 구독자 토큰으로 둔다. 주소는 빌드할 때 `-PkiteRelayUrl=https://…` 로 넣는다 (저장소에는 적지 않는다).
- 이번 달 한도를 넘으면 HTTP 402. 앱은 "이번 달 사용량을 모두 썼습니다"로 보여 준다.
- `GET /v1/usage` (헤더 `x-api-key: <토큰>`) 로 이번 달 사용량(`used_usd`, `budget_usd`, `plan`)을 묻는다.
