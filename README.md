# 차쉼 (Chashim)

차 안에서 기다리는 시간(전기차 충전, 픽업 대기, 휴게소 휴식)을 **차의 공조·시트 열선·와이퍼·창문이 영상에 맞춰 움직이는 4D 휴식 체험**으로 바꾸는 Pleos Connect 앱입니다.
현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## 현재 데모 범위

- **힐링 모드 "비 오는 숲"** (5장면, 약 3분)

  | 장면 | 4D 효과 |
  |---|---|
  | 안개 낀 숲 | 바람 1단, 23°C |
  | 빗방울 | 와이퍼 4초, 바람 2단 |
  | 폭우 | 바람 3단, 22°C, 번개 플래시 |
  | 처마 밑 | 시트 열선 1단계, 24°C |
  | 비 그친 뒤 | 열선 끄기, 창문 10% |

- **안전 규칙**
  - P단에서만 시작
  - 기어가 바뀌면 즉시 정지
  - 창문은 최대 20%
  - 바람과 온도는 천천히 변경
  - 끝나거나 멈추면 시작 전 설정으로 복구

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
```

## 영상·소리 넣기
아래 경로에 파일을 넣으면 앱이 자동으로 재생합니다. 파일이 없으면 앱이 그린 빗줄기 화면이 대신 나옵니다.

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
├── engine/SessionEngine.kt  장면 재생, 시점별 효과 실행, 안전 정지, 복구
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
