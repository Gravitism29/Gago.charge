# 가는 김에 충전

Pleos Connect용 전기차 일정 앱입니다. 볼일을 보는 동안 충전하면 다음 약속에 맞출 수 있는지, 장소별 예상 도착 시각과 남는 시간을 비교합니다.

예를 들어 “은평점에서 25분 장 보고 4시 10분까지 학교 가야 해”라고 말하면 마트와 학교가 방문 순서에 들어갑니다. 시간이나 장소는 화면에서 고칠 수 있고, 원하는 경로를 확인하면 차량 내비에 반영합니다.

[시제품 APK 다운로드](https://github.com/Gravitism29/Gago.charge/raw/refs/heads/main/apk/gakim-charge-0.3.1-debug.apk) · 0.3.1 · Pleos 에뮬레이터용 디버그 빌드

차량 상태·음성 인식·내비 연동은 Pleos SDK를 사용합니다. 후보 장소, 충전기 상태, 일부 구간의 거리와 소요 시간에는 화면에 표시된 시연값을 사용합니다.

## 빌드와 테스트

JDK 17과 Android SDK Platform 35가 필요합니다. Windows에서 프로젝트 루트의 다음 명령을 실행합니다.

```powershell
.\gradlew.bat :domain:test :app:testDebugUnitTest :app:assembleDebug "-Pgakim.useFakes=false"
```
