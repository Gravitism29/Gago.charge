# 가는 김에 충전

Pleos Connect용 전기차 일정 앱입니다. 볼일을 보는 동안 충전하면 다음 약속에 맞출 수 있는지, 장소별 예상 도착 시각과 남는 시간을 비교합니다.

예를 들어 “25분 장 보고 4시 10분까지 학교 가야 해”라고 말하면 마트와 학교가 방문 순서에 들어갑니다. 시간이나 장소는 화면에서 고칠 수 있고, 원하는 경로를 확인하면 차량 내비에 반영합니다.

[APK 다운로드](https://github.com/Gravitism29/Gago.charge/raw/refs/heads/main/apk/%EA%B0%80%EB%8A%94%20%EA%B9%80%EC%97%90%20%EC%B6%A9%EC%A0%84_%EA%B0%80%EB%8A%94%20%EA%B9%80%EC%97%90%20%EC%B6%A9%EC%A0%84_release.apk) · 0.4.0 · `가는 김에 충전_가는 김에 충전_release.apk` · Pleos 에뮬레이터용(디버그 서명)

## 사용하는 데이터

| 항목 | 출처 | API 키 |
|---|---|---|
| 배터리 잔량, 주행 가능 거리, 기어, 음성 인식·안내, 내비 경로 | Pleos Connect SDK | 필요 없음 |
| 주변 대형마트 검색, 좌표의 행정구역 | 카카오 로컬 API | 카카오 REST API 키 |
| 구간별 거리와 소요 시간 | 카카오모빌리티 길찾기 API | 카카오 REST API 키 |
| 충전소 위치·출력·사용 가능 여부 | 한국환경공단 전기자동차 충전소 정보(공공데이터포털) | 공공데이터포털 인증키 |
| 대형마트 의무휴업일, 폐점 점포 | 앱에 포함된 자료(`app/src/main/assets`, 수집 도구 `tools/`) | 필요 없음 |

- 충전소의 주차·연결 시간과 충전 속도는 실측값이 없어 화면에 `추정`으로 표시합니다.
- 의무휴업일은 2026년 11~12월 공지 기준이며, 그 이후 날짜는 규칙에 따른 `(추정)`으로 표시합니다.

## API 키

- **위 APK에는 API 키가 들어 있습니다.** 내려받아 설치하면 실제 마트·경로·충전소 데이터로 동작합니다.
- **이 저장소의 소스에는 API 키가 없습니다.** 키 값은 비워 둔 채로 올리며, 소스를 직접 빌드하려면 본인의 키를 따로 넣어야 합니다. 키 없이 빌드하면 마트·경로·충전소가 화면에 `시연 값`으로 표시된 예시 데이터로 동작합니다.

키를 넣는 방법은 둘 중 하나입니다.

1. `local.properties.example`의 두 항목(`kakao.restApiKey`, `datagokr.serviceKey`)을 프로젝트 루트의 `local.properties`에 복사해 값을 채웁니다. 이 파일은 git 제외 대상이라 커밋되지 않습니다.
2. 환경 변수 `KAKAO_REST_API_KEY`, `DATAGOKR_SERVICE_KEY`로 넘깁니다.

키 발급:

- 카카오: [developers.kakao.com](https://developers.kakao.com) → 내 애플리케이션 → REST API 키. 카카오맵(로컬)과 카카오모빌리티 길찾기 사용 설정이 필요합니다.
- 충전소: [data.go.kr](https://www.data.go.kr) → 한국환경공단_전기자동차 충전소 정보 → 활용신청.

## 빌드와 테스트

JDK 17과 Android SDK Platform 35가 필요합니다. Windows에서 프로젝트 루트의 다음 명령을 실행합니다.

```powershell
.\gradlew.bat :domain:test :app:testDebugUnitTest :app:assembleDebug "-Pgakim.useFakes=false"
```

Pleos 플랫폼은 주행 중(기어 D)에는 앱 화면을 가립니다. 에뮬레이터에서는 기어를 P에 두고 실행합니다.
