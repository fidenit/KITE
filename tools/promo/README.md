# 소개 영상 다시 만들기

`KITE-promo.mp4`(1920x1080, 초당 60장, 약 2분)를 만든 도구다. 앱을 화면 밖에 띄워 대본대로 돌리며 프레임마다 찍고, 찍을 때 남긴 카드·마우스 위치로 편집한다.

## 1. 빌드

저장소 맨 위에서 `.\run.ps1 build`. 녹화 기능은 앱 안의 확인용 환경 변수로 들어 있다 (아래 표).

## 2. 녹화

```powershell
powershell -File tools\promo\record-all.ps1            # 네 토막 전부
powershell -File tools\promo\record-all.ps1 -Only e    # 한 토막만
```

시험 창은 화면 밖에 뜨고 키보드·마우스를 건드리지 않는다. 결과는 이 폴더에 `a.mp4` `b.mp4` `e.mp4` `d.mp4`(2160x1215, 화면 배율 1.35 = 논리 1600x900)와 각각의 `.jsonl`(프레임마다 카드 자리, 마우스 자리, 지금 일), `.done` 으로 남는다. 저장소에는 넣지 않는다.

| 토막 | 내용 | 대본 |
|---|---|---|
| a | 여는 장면 + 흐름 한 번 (워크숍 계획) | `assets/promo_scenes.txt`, 경로 `assets/promo_routes` |
| b | 내용에 맞는 카드 (뉴스레터, 카드마다 확대) | `scenes_b.txt` |
| e | 긴 프로젝트: 밤사이 진행 + 컨펌이 하나씩 내 차례로 | `scenes_e.txt`, 시작 보드 `seed_e.ops` |
| d | 자유로운 화면 조작 + 닫는 장면 | 시작 보드 `assets/seed_groups.ops` |

AI 응답은 실제 AI 대신 정해 둔 장면이다 (시연 모드). b 는 실제 AI 가 만든 결과를 그대로 옮긴 것이다.

## 3. 편집

```powershell
python tools\promo\edit.py KITE-promo.mp4              # 최종본 (약 6분)
python tools\promo\edit.py preview.mp4 --preview       # 960x540 미리보기 (약 4분)
```

자막 문구, 확대할 곳, 챕터 제목은 `edit.py` 의 `build()` 에 있다. 커서와 클릭 물결은 편집할 때 그린다 (화면 캡처에는 마우스가 찍히지 않는다). Python 에 Pillow 가 있어야 하고, `dupcheck.py` 는 numpy 도 쓴다. ffmpeg 가 PATH 에 있어야 한다.

## 4. 확인

```powershell
python tools\promo\sheet.py 영상.mp4 모음.png 시작초 끝초 간격초 [열] [칸폭]   # 프레임 모음 그림
python tools\promo\dupcheck.py 영상.mp4                                         # 움직이는 중에 멈춘 프레임 세기
```

## 앱 쪽 환경 변수

| 변수 | 하는 일 |
|---|---|
| `LYBOARD_VIDEO=파일.mp4` | 프레임마다 화면을 ffmpeg 로 넘기고 `파일.mp4.jsonl` 에 위치를 적는다. 이때만 vsync 를 끄고, 흉내 낸 손은 초당 60걸음으로 움직인다. 할 일이 끝나고 조용해지면 스스로 마친다 |
| `LYBOARD_VIDEO_FPS` `_MAX` `_TAIL` | 초당 장수(60), 최대 길이(300초), 끝난 뒤 더 찍는 시간(2500ms) |
| `LYBOARD_SCALE=1.35` | 화면 배율. 같은 배치를 더 촘촘한 화소로 그린다 (`LYBOARD_ZOOM` 은 처음 카메라 배율이라 다르다) |
| `LYBOARD_DEMO_SEED=ops` | 시연 모드에서도 이 조작 기록으로 보드를 채워 두고 시작한다 |
| `LYBOARD_DEMO_LINE_MS` | 시연 대본을 줄마다 이만큼 쉬어 가며 흘린다 (카드가 채워지는 모습이 보이게) |
| `LYBOARD_PROMO_MODE=intro\|outro` | 소개 장면 가운데 여는 장면만, 또는 닫는 장면만 |

자동 재생 대본(`LYBOARD_AUTOPLAY`, `|` 로 구분)에서 쓰는 걸음: `>글`(입력창에 한 글자씩 적은 뒤 보냄), `#이름`(영상 기록에 그 시각을 표시), `~초`(쉼), `@click x y`, `@dclick x y`, `@drag x y dx dy [프레임]`, `@wheel x y 줄수`, `@move x y`, `@wait 프레임`. 좌표는 논리 좌표다.
