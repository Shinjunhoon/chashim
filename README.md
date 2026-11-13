# 차쉼 (Chashim)

차 안에서 기다리는 시간(전기차 충전, 픽업 대기, 휴게소 휴식)을 **차의 공조·시트 열선·와이퍼·창문이 영상에 맞춰 움직이는 4D 체험**으로 바꾸는 Pleos Connect 앱입니다.
쉬고 싶을 땐 **힐링 모드**, 아이와 함께라면 핸들로 조종하는 **엔터테인 모드**를 고릅니다.
현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## 현재 데모 범위

- **힐링 모드 "비 오는 숲"** (5장면, 충전 시간에 맞춰 길이 조절)

  | 장면 | 역할 | 4D 효과 |
  |---|---|---|
  | 안개 낀 숲 | 도입 | 무드등 초록 |
  | 빗방울 | 도입 | 와이퍼, 바람 잠깐 +1단, 무드등 파랑 |
  | 폭우 | 중간 | 돌풍 +2~3단, 와이퍼, 무드등 남색, 번개 때 화면과 무드등이 하얗게 번쩍 |
  | 처마 밑 | 중간 | 시트 열선 1단계, 무드등 주황 |
  | 비 그친 뒤 | 마무리 | 열선 끄기, 창문 10%, 무드등 노랑 |

  - **에어컨 온도는 건드리지 않습니다.** 운전자가 맞춘 온도가 그대로 유지됩니다.
  - 바람은 운전자가 맞춘 세기를 기준으로 **잠깐 세졌다가 돌아오는 돌풍**으로만 움직입니다.

- **충전 연동**
  - 충전구를 꽂으면(P단) "충전을 시작했어요" 안내창이 뜨고, 완료까지 남은 시간을 보여 줍니다.
  - **충전 시간 맞춤**: 충전 속도를 재서(약 20초) 목표 80%까지 남은 시간을 계산합니다.
    - 도입 장면을 재생한 뒤, 남은 시간 동안 중간 장면(폭우·처마 밑)을 반복하며 길이를 맞춥니다.
    - 충전이 끝날 즈음 마무리 장면(비 그친 뒤)이 나오고 "충전이 끝났어요. 출발하셔도 돼요"로 끝납니다.
  - 충전기를 분리하면 바로 마무리하고 설정을 복구합니다.
  - 충전 중이 아니면 5장면을 한 번 재생합니다 (약 3분).

- **실내 온도 맞춤**
  - 시작할 때 실내 현재 온도(`getHvacTemperatureCurrent`)를 읽고 **열선과 바람**을 조절합니다.

    | 실내 온도 | 조절 |
    |---|---|
    | 20°C 이하 (쌀쌀함) | 첫 장면부터 열선 1단계, 처마 밑 열선 2단계 |
    | 26°C 이상 (더움) | 열선 끄기, 돌풍 한 단계 더 세게 |
    | 그 사이 | 기본값 |

  - 판단 결과를 영상 위와 4D 패널에 보여 줍니다. 예: "차 안이 19°C로 쌀쌀해서 열선을 먼저 켜 드릴게요"
  - 에뮬레이터 실내 온도는 19.1°C로 고정이라, 더운 경우는 테스트 명령으로 확인합니다.

- **엔터테인 모드 "제주 하늘 비행"** (약 2분 30초, 핸들로 조종)

  | 구간 | 미션 | 4D 효과 |
  |---|---|---|
  | 이륙 | 핸들 감도 확인, 3-2-1 | 돌풍 +2단, 무드등 하늘 파랑 |
  | 해안 비행 | 링 4개, 끝에 갈림길 | 창문 10%, 무드등 청록 |
  | 폭포 (왼쪽) / 오름 능선 (오른쪽) | 링 2개 | 폭포: 와이퍼 + 돌풍 / 능선: 돌풍 3번 |
  | 한라산 구름 위 | 먹구름 2개 피하기, 급강하 링 3개 | 창문 닫기, 돌풍 +3단, 와이퍼 |
  | 일출 착륙 | 감상 | 시트 열선 1단계, 무드등 주황 |

  - 핸들: ±60°가 화면 끝, 데드존 ±3°, 떨림 필터. 조준점이 움직이고 화면이 기울어지며 시야가 따라 움직입니다.
  - 갈림길: 안내가 뜬 뒤 6초 안에 핸들을 45° 이상 꺾은 쪽으로 갑니다. 안 꺾으면 폭포.
  - 브레이크: 밟는 동안 0.5배속 호버링, 바람 1단.
  - 링 통과 +100 (콤보 3번째부터 +200), 먹구름 충돌 -100. 최고 기록을 저장합니다.
  - 링 통과·콤보·충돌 때마다 바람이 순간적으로 세지거나 멈춥니다. 같은 장치에 300ms 안에 두 번 명령하지 않습니다.
  - SDK의 핸들 각도는 왼쪽이 +입니다 (콘솔 오른쪽 40° → SDK -40°). 앱에서 부호를 바꿔 오른쪽을 +로 씁니다.

- **안전 규칙**
  - P단에서만 시작
  - 기어가 바뀌면 즉시 정지
  - 창문은 최대 20%
  - 에어컨 온도는 바꾸지 않음
  - 끝나거나 멈추면 시작 전 설정(바람, 온도, 열선, 창문, 무드등)으로 복구

## 개발 환경 설정

### 1. Android Studio
- 최신 안정 버전을 설치합니다. 확인한 버전: Rabbit 2 | 2026.2.2
- **Apple 칩 Mac은 반드시 `mac_arm.dmg`** 버전을 설치하세요. Intel용(`mac.dmg`)은 실행되지 않습니다.

### 2. Gradle JDK를 17로 설정 (필수)
이 프로젝트는 Gradle 8.10.2 / AGP 8.8.2를 씁니다. Android Studio에 들어 있는 기본 Java(25)로는 빌드되지 않습니다.

1. JDK 17을 설치합니다. 예: Amazon Corretto 17
2. Android Studio에서 `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Gradle`로 갑니다.
3. **Gradle JDK**를 **17**로 바꿉니다.

명령줄에서 빌드할 때는 아래처럼 실행합니다.
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug
```

### 3. Pleos Connect 에뮬레이터
1. `SDK Manager` → `SDK Tools`에서 **Android Emulator**를 최신으로 업데이트합니다.
2. `SDK Update Sites` → `+`
   - Name: `Pleos Connect System Image`
   - URL (Apple 칩 Mac): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
   - URL (Windows/Linux): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
3. `SDK Platforms` → `Show package details`를 체크하고, API 34 아래 **Pleos Connect v2.1.0 APIs** 이미지를 설치합니다.
4. `Device Manager` → `+` → **Automotive** → **Pleos Connect v2.1.0**을 고릅니다.
   - 시스템 이미지: **Pleos Connect v2.1.0 APIs** (Google APIs 이미지 아님)
   - Additional settings: **Boot = Cold boot**, **RAM = 4 GB**

### 4. 버추얼 콘솔 (차 상태 조작)
```bash
adb forward tcp:8088 tcp:8088
```
브라우저에서 http://127.0.0.1:8088/ 을 엽니다.
에뮬레이터를 다시 켜면 이 명령을 다시 실행해야 합니다.

> ⚠️ 기어가 **D**이면 "주행 중에는 사용할 수 없습니다" 화면이 앱을 가립니다. 콘솔에서 기어를 **P**로 바꾸세요.

> ⚠️ **바람·온도 조절이 실패하거나(`error code: 2`) 설정해도 원래 값으로 돌아갈 때**
> 1. 콘솔 상단의 `AVD` 선택칸을 **Disabled**로 바꿉니다. 콘솔이 에뮬레이터와 연결됩니다.
> 2. 다시 **Enabled**로 바꿉니다.
> 3. 콘솔 공조 패널에서 공조 전원을 켭니다.
>
> 이렇게 하면 앱에서 바꾼 값이 유지되고 콘솔 화면에도 같이 표시됩니다.

## 실행
1. Android Studio에서 이 폴더를 엽니다 (`File` → `Open`).
2. 기기를 **Pleos Connect v2.1.0**으로 고르고 ▶ 버튼을 누릅니다.
3. 에뮬레이터에서 **차쉼** → **시작하기**를 누릅니다.

테스트용 명령:
```bash
adb shell am start -n com.chashim.app/.MainActivity --es cmd start   # 세션 시작
adb shell am start -n com.chashim.app/.MainActivity --es cmd stop    # 정지 후 복구
adb shell am start -n com.chashim.app/.MainActivity --ef cabin 28 --es cmd start   # 실내 28°C라고 가정하고 시작
adb shell am start -n com.chashim.app/.MainActivity --ez cabin_reset true          # 가정 해제 (차 값 사용)
adb shell am start -n com.chashim.app/.MainActivity --ef charge_kw 50              # 충전 속도 50kW로 가정 (0이면 해제)
adb shell am start -n com.chashim.app/.MainActivity --es cmd fly                   # 엔터테인 비행 시작
adb shell am start -n com.chashim.app/.MainActivity --ef steer 30                  # 핸들 오른쪽 30°로 가정 (--ez steer_reset true 로 해제)
adb shell am start -n com.chashim.app/.MainActivity --ez brake true                # 브레이크 밟음으로 가정 (--ez brake_reset true 로 해제)
```

시연 전 초기화 (기어 P, 배터리 77%, 바람 3단, 22°C, 창문 닫기, 무드등 흰색, 앱 재시작):
```bash
tools/reset_demo.sh 77
```

버추얼 콘솔에서 핸들·브레이크 조작: 콘솔의 핸들을 드래그하거나
`curl -X PUT "http://127.0.0.1:8088/vehicle/controls?speed=0&accelerator=0&brake=0&steering=40"`

충전 시뮬레이터 (버추얼 콘솔로 충전구를 열고 SoC를 실제 속도처럼 올림):
```bash
tools/simulate_charging.sh 77 80 50   # 77% → 80%, 50kW (1%마다 약 56초)
```
> 시뮬레이터가 도는 동안 콘솔 탭을 앞에 띄워 두고 Charge Port는 건드리지 마세요.

## 영상·소리
영상과 소리 파일은 저장소에 들어 있습니다. 바꾸고 싶으면 아래 경로에 같은 이름으로 넣습니다. 파일을 넣으면 앱이 자동으로 재생합니다. 파일이 없으면 앱이 그린 빗줄기 화면이 대신 나옵니다.

```
app/src/main/assets/rain_forest/
├── 01_mist.mp4        안개 낀 숲
├── 02_raindrops.mp4   나뭇잎 빗방울
├── 03_heavy_rain.mp4  폭우 숲
├── 04_eaves.mp4       처마 밑 비
├── 05_sunlight.mp4    비 그친 뒤 햇살
├── rain_light.mp3
├── rain_heavy.mp3
└── birds.mp3
```

- 영상: 1080p 이하 mp4, 카메라가 고정된 영상 (Pexels 등 상업 이용 가능한 라이선스)
- 소리: Pixabay 등. 출처는 따로 기록해 둡니다.

## 구조
```
app/src/main/java/com/chashim/app/
├── MainActivity.kt          화면 전환
├── vehicle/VehicleGateway.kt  Pleos Vehicle SDK 래퍼 (지원 범위 조회, 제어, 상태 저장/복구, 기어·충전 감지)
├── engine/Theme.kt          테마·장면·효과 정의 (비 오는 숲)
├── engine/SessionEngine.kt  장면 재생, 시점별 효과 실행, 충전 시간 맞춤, 안전 정지, 복구
├── engine/ChargeEstimator.kt  충전 속도 측정, 완료 예상 시간 계산
├── engine/ClimateAdapt.kt   실내 온도에 따른 열선·바람 조절
├── engine/flight/Course.kt  비행 코스 정의 (구간, 링, 먹구름, 갈림길, 구간별 4D 효과)
├── engine/flight/FlightEngine.kt  핸들 입력, 링·먹구름 판정, 점수, 갈림길, 호버링, 이벤트 4D 반응, 안전 정지·복구
└── ui/                      홈, 재생(영상 + 4D 효과 패널), 종료 화면
```

## Vehicle SDK 메모
- SDK: `ai.pleos.playground:Vehicle:2.3.2` (저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`)
- 에뮬레이터에서는 **매니페스트에 권한을 적기만 하면** 제어 API가 동작합니다. 별도 승인은 필요 없습니다.
- 기능마다 지원하는 위치(area)가 다릅니다. 예: 바람과 공조 전원의 area가 다릅니다. 항상 `check...Capabilities`로 받은 area를 쓰세요.
- 에뮬레이터 지원 범위:
  - 바람: 1~8단
  - 온도: 15~30°C
  - 열선: 0~3단계 (좌석 4곳)
  - 창문: 0~10
  - 와이퍼: OFF / CONTINUOUS_LEVEL_1 / CONTINUOUS_LEVEL_3 / AUTO
  - 무드등: 색은 0xRRGGBB 정수로 주고받습니다. 권한은 `CAR_LIGHTS`, `CONTROL_CAR_LIGHTS`가 필요합니다. 버추얼 콘솔 화면의 실내 조명 색에도 반영됩니다.

## 사용한 영상·소리 출처
- 영상 (Pexels 무료 라이선스)
  - 01: https://www.pexels.com/video/a-foggy-day-in-the-forest-4296589/
  - 02: https://www.pexels.com/video/raindrops-falling-on-leaves-9080287/
  - 03: https://www.pexels.com/video/heavy-rain-falling-on-green-trees-14213651/
  - 04: https://www.pexels.com/video/raining-drops-falling-from-the-roof-6252550/
  - 05: https://www.pexels.com/video/sunlight-passing-through-trees-5535406/
- 비행 영상 (Pexels 무료 라이선스, `app/src/main/assets/jeju_flight/`)
  - 01 이륙: https://www.pexels.com/video/drone-flying-above-the-sea-surface-3638130/
  - 02 해안: https://www.pexels.com/video/drone-flying-over-cliffs-and-blue-ocean-with-waves-16298299/
  - 03a 폭포: https://www.pexels.com/video/drone-footage-of-a-waterfall-8226944/
  - 03b 능선: https://www.pexels.com/video/drone-flying-over-the-mountain-peak-4763824/
  - 04 구름: https://www.pexels.com/video/video-footage-of-flying-over-the-clouds-in-the-air-3129724/
  - 05 일출: https://www.pexels.com/video/drone-video-of-a-sunrise-over-the-ocean-13326421/
  - 실제 제주 촬영본이 아닌 무료 드론 영상입니다. 실제 제주 영상으로 바꾸려면 같은 이름으로 넣으면 됩니다.
- 소리 (Pixabay 콘텐츠 라이선스)
  - rain_light: https://pixabay.com/sound-effects/nature-light-rain-109591/
  - rain_heavy: https://pixabay.com/sound-effects/nature-heavy-rain-thunder-145345/
  - birds: https://pixabay.com/sound-effects/nature-birds-forest-nature-445379/
