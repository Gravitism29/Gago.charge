# 가는 김에 충전

볼일을 보는 동안 전기차를 충전해도 다음 일정에 늦지 않는지 계산하고, 장소별 예상 완료 시각을 비교해 차량 내비게이션으로 안내하는 Pleos Connect 앱입니다.

## 실행 방법

1. **에뮬레이터 준비**: Pleos Connect 에뮬레이터를 실행하고 기어를 **P(주차)**에 둡니다.
   - 주행(D) 상태에서는 Pleos 플랫폼 정책에 따라 앱 화면이 제한됩니다.
2. **APK 설치**: [배포용 APK](https://github.com/Gravitism29/Gago.charge/raw/refs/heads/main/apk/%EA%B0%80%EB%8A%94%20%EA%B9%80%EC%97%90%20%EC%B6%A9%EC%A0%84_%EA%B0%80%EB%8A%94%20%EA%B9%80%EC%97%90%20%EC%B6%A9%EC%A0%84_release.apk)를 내려받은 폴더에서 아래 명령어로 설치합니다.

   ```powershell
   adb install -r "가는 김에 충전_가는 김에 충전_release.apk"
   ```

3. **앱 실행**: 에뮬레이터의 앱 목록에서 **가는 김에 충전**을 실행합니다.

### 사용 방법

1. **일정 입력**: 마이크 버튼을 누르고 *“25분 장 보고 4시 10분까지 학교 가야 해”*처럼 머무는 시간과 도착해야 하는 시각을 말합니다. 화면의 예시 문장을 눌러도 됩니다.
2. **시간 비교**: 주변 마트별로 **충전할 때**와 **충전하지 않을 때** 일정이 끝나는 시각이 나란히 표시됩니다.
3. **경로 안내**: 들를 곳을 고르고 확인하면 차량 내비게이션에 경로가 설정됩니다.

## 테스트 환경 및 필요한 설정

- **별도 설정 불필요**: 배포용 APK에는 API 키가 포함되어 있어, 설치하면 실제 마트·경로·충전소 데이터로 바로 동작합니다.
- **네트워크 연결 필요**: 실시간 데이터 조회를 위해 아래 외부 API를 호출합니다.
  - 카카오 로컬 API, 카카오모빌리티 길찾기 API
  - 한국환경공단 전기차 충전소 정보(공공데이터포털)
- **차량 데이터 연동**: 배터리 잔량과 주행 가능 거리는 에뮬레이터의 차량 설정값을 실시간으로 읽어 계산에 반영합니다.
- **소스 코드를 직접 빌드할 때**: 저장소의 소스 코드에는 API 키가 포함되어 있지 않습니다. `local.properties.example`을 참고해 `local.properties`에 본인의 API 키를 입력한 뒤 빌드해야 하며, 키 없이 빌드하면 내장된 예시 데이터로 동작합니다.

| 항목 | 내용 |
| :--- | :--- |
| **개발 및 빌드 환경** | Windows 11, JDK 17, Android `minSdk 28` |
| **Pleos Connect SDK** | `Vehicle` 2.3.2, `NaviHelper` 3.0.5, `SpeechToText` 3.3.1, `TextToSpeech` 3.3.1 |
| **사용 권한** | `pleos.car.permission.CAR_ENERGY`, `CAR_DRIVE`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `NAVI_CUSTOM_MAP`, `STT_SERVICE`, `TTS_SERVICE`, `android.permission.INTERNET` |

## 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격

- **에뮬레이터 버전**: Pleos Connect 에뮬레이터 v2.1.0 (`system-images;android-34;pleos_car_emulator;x86_64`)
- **화면 규격**: 2560 × 1440 px, 가로, `mdpi`(160 dpi)
- **앱 표시 영역**: 화면 오른쪽 약 1700 × 1210 px

## APK 파일 위치

- [`apk/가는 김에 충전_가는 김에 충전_release.apk`](https://github.com/Gravitism29/Gago.charge/raw/refs/heads/main/apk/%EA%B0%80%EB%8A%94%20%EA%B9%80%EC%97%90%20%EC%B6%A9%EC%A0%84_%EA%B0%80%EB%8A%94%20%EA%B9%80%EC%97%90%20%EC%B6%A9%EC%A0%84_release.apk)
