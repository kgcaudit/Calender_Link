# OLO Calendar 구상안

작성일: 2026-10-16 · 상태: 구상안(결정 전) · 디자인 기준: `kgcaudit/OLO-Design`

ChatGPT 정리본(`calendar-app-plan.md`)은 참고만 하고, 외부 자료와 OLO-Design 저장소를 확인해 방향을 다시 잡았다.

---

## 1. 핵심 판단

| # | 판단 | 이유 |
|---|------|------|
| 1 | **동기화 엔진을 직접 만들지 않는다.** 휴대폰 OS의 캘린더 저장소를 그대로 읽고 쓴다. | 갤럭시: 삼성 캘린더와 같은 Android Calendar Provider(`CalendarContract`)를 쓴다. Google 계정의 동기화 어댑터가 Google과 동기화한다. 아이폰: 아이폰 캘린더와 같은 EventKit 저장소를 쓴다. 설정에 추가한 Google 계정이 CalDAV로 동기화한다. 그래서 OLO Calendar는 **같은 데이터를 보여 주는 또 하나의 화면**이 된다. |
| 2 | Google Calendar API는 **나중에, 필요할 때만** 쓴다(PC 웹 등). | API 방식은 서버, OAuth 토큰 보관, 충돌 처리, 푸시 채널 갱신을 모두 직접 해야 한다. 앱이 *Testing* 상태면 refresh token이 7일 만에 만료되고, *In production*으로 바꿔야 오래 유지된다. Calendar 범위는 Google 검토 대상이다. |
| 3 | **Kotlin Multiplatform + Compose Multiplatform**으로 만든다. | OLO 가족 앱은 모두 Kotlin/Compose(`Theme.kt`)다. 그래서 OLO 토큰을 공용 모듈 하나로 iOS·Android가 같이 쓸 수 있다. Compose for iOS는 1.8.0(2025-05)부터 Stable이다. |
| 4 | **Android(갤럭시)를 먼저**, iOS는 2단계로 한다. | 다른 OLO 앱과 같은 APK 배포 흐름을 쓸 수 있다. iOS는 macOS·Xcode가 필요하고 서명·배포 조건도 있다(5장). |

### ChatGPT 정리본과 다른 점

| 항목 | ChatGPT 정리본 | 이번 구상안 |
|------|----------------|-------------|
| 앱의 데이터 경로 | 앱 → Google Calendar API → Google | 앱 → OS 캘린더 저장소 ⇄ (OS가 동기화) ⇄ Google |
| 서버 | 호스팅 + PHP 서버 필요 | 1~3단계에는 필요 없음 |
| 로그인 | 앱 안에서 Google OAuth | 휴대폰에 이미 추가된 Google 계정을 사용. 앱은 캘린더 권한만 요청 |
| 오프라인 | 별도 요구사항 | OS 저장소에 저장했다가 연결되면 OS가 올려 보냄 |
| 삼성·아이폰 캘린더와 일치 | Google을 거쳐 나중에 맞춰짐 | 같은 저장소라 **같은 기기 안에서는 바로 일치** |
| 분류(개인·업무·건강) | 미정 | **분류마다 별도 Google 캘린더**(6장) |
| 앱 형태 | PWA 먼저 | 네이티브 설치 앱(KMP). PWA는 OS 캘린더 저장소에 접근할 수 없음 |

---

## 2. 구조

```text
            ┌──────────── 갤럭시 ────────────┐      ┌──────────── 아이폰 ────────────┐
            │ OLO Calendar   삼성 캘린더      │      │ OLO Calendar   아이폰 캘린더   │
            │      \            /            │      │      \            /           │
            │   Android Calendar Provider    │      │      EventKit 저장소           │
            │   (Google 계정 동기화 어댑터)   │      │   (Google 계정, CalDAV)        │
            └──────────────┬─────────────────┘      └──────────────┬────────────────┘
                           └──────────────⇅  Google 캘린더  ⇅───────┘
                                              │
                                PC: calendar.google.com
                                (4단계 선택: OLO 웹 + Calendar API)
```

- 일정 원본은 Google 캘린더 하나다. 각 기기는 OS가 Google과 동기화한다.
- 앱 코드는 `CalendarStore` 인터페이스 하나에 붙고, 플랫폼별 구현은 둘이다.
  - `AndroidCalendarStore`: `CalendarContract`(Calendars / Events / Instances / Reminders)
  - `IosCalendarStore`: EventKit(`EKEventStore`, `EKCalendar`, `EKEvent`), Kotlin/Native interop

### 프로젝트 골격(제안)

```text
shared/
  design/      OLO 토큰(색·모양·글자) — OLO-Design에서 가져옴
  domain/      Event, CalendarInfo, Recurrence, 시간대 규칙
  store/       CalendarStore (expect) + android/ios 구현 (actual)
  ui/          Compose 화면: 월·주·목록·편집·연결상태
androidApp/    Android 진입점, Glance 위젯
iosApp/        iOS 진입점(Xcode), WidgetKit 위젯(SwiftUI)
```

---

## 3. 화면 구성

| 화면 | 내용 |
|------|------|
| 월(기본) | 6주 격자, 날짜 칸 10dp. 오늘은 Clay 타일에 흰 숫자. 일정은 캘린더 색 칩(6dp). 음력과 공휴일 표시 |
| 주 / 일 | 시간 축 타임라인, 일정 카드 14dp |
| 목록(아젠다) | 날짜별 목록. 행 앞에 캘린더 색 타일 |
| 일정 편집 | 바텀시트 18dp. 제목, 종일, 시작·종료, 반복, 장소, 메모, 알림, **저장할 캘린더** |
| 캘린더·연결 상태 | 캘린더마다 `Google 동기화` / `이 기기만` / `iCloud` 표시. 동기화 상태 배지 |
| 설정 | 기본 저장 캘린더, 주 시작 요일, 음력 표시, 공휴일 캘린더 |
| 위젯 | 오늘 일정, 미니 월 달력 |

주 행동(새 일정)은 Clay 단색 FAB이다. 보조 행동(빠른 추가, 오늘로 이동)은 `secondaryContainer`를 쓴다.

---

## 4. OLO 세계관 적용

OLO-Design의 규칙은 바꾸지 않고 그대로 가져온다. 캘린더에 새로 필요한 값은 **OLO-Design에 먼저 추가한 뒤** 앱으로 가져온다(세계관 규칙: 변경은 OLO-Design에서 시작).

| 세계관 규칙 | OLO Calendar 적용 |
|-------------|-------------------|
| Clay `#B95B3B` on Ivory `#F7F4EF`, Material-You 없음 | 같은 팔레트. 다크는 ClayLight `#E8A183` / `#181613` |
| 종류는 **명도가 아니라 색상(hue)**으로 구분 | 캘린더(개인·업무·건강 등)마다 OLO 타일 hue 하나 |
| 모서리 6/10/14/18/20dp, 알약 버튼 없음 | 칩 6, 날짜 칸·버튼 10, 일정 카드 14, 시트 18, 다이얼로그 20 |
| 글자: headline 28/24/20, titleLarge 19만 축소 | 월 제목 headlineSmall 20. 날짜 숫자는 고정폭 숫자 |
| 상태색은 차가운 계열(브랜드와 구분) | 동기화 상태: running=동기화 중, waiting=대기, failed=실패, done=완료 |
| 오류는 Crimson `#A50E2E`(Clay와 구분) | 권한 거부, 계정 동기화 꺼짐 안내 |
| **소리는 움직임을 따른다**(`tokens/sound.md`) | 다음 달 넘김은 오른쪽에서 +0.7 → −0.3. 이전 달은 반대. 일정을 왼쪽으로 밀어 지우면 왼쪽으로 sweep. 이어폰·가로·600dp 이상에서만 적용하고 앱 내 설정은 없음 |
| 런처: Clay 명도 그라데이션 + 흰 붓 마크 하나 | 마크 제안 A: 붓으로 그린 **달력 낱장**(위쪽 고리 두 점) + 한 칸에 찍힌 **낙관 같은 점**(오늘). 제안 B: 가로 붓획 세 줄(한 주) 위에 점 하나 |
| 심볼 팩 재사용 | `003 일정,캘린더`, `120 시간,기록`, `107 회의,미팅`, `024 알림`, `115 추가` |

### 캘린더 색 → OLO hue 매핑(OLO-Design에 등록 제안)

Google 캘린더 색은 원본에서 바꾸지 않는다. **OLO 화면에서만** 가장 가까운 OLO hue로 바꿔 보여 준다. 삼성·아이폰 캘린더에서는 원래 색이 보인다.

| 예시 캘린더 | OLO hue | Light |
|-------------|---------|-------|
| 개인 | folder(Clay) | `#B95B3B` |
| 업무 | document(slate) | `#55606B` |
| 건강 | image(green) | `#2E8B6B` |
| 가족·모임 | audio(rose) | `#B04A6A` |
| 여행·이동 | code(teal) | `#3E7F80` |
| 공부·독서 | video(violet) | `#6A5A9E` |
| 공휴일 | (텍스트 Crimson, 타일 없음) | `#A50E2E` |

---

## 5. 플랫폼별 확인 사항

### Android(갤럭시)
- 권한: `READ_CALENDAR`, `WRITE_CALENDAR`.
- 종일 일정은 Provider 규칙상 UTC 자정 기준으로 저장해야 한다.
- 계정 유형으로 저장소를 구분한다: `com.google` = Google 동기화. 로컬 / 삼성 계정 = `이 기기만`.
- "지금 동기화"는 `ContentResolver.requestSync`로 요청한다.
- 삼성 캘린더 전용 기능(스티커 등)은 Google로 넘어가지 않을 수 있다. 실기기에서 확인한다.

### iOS(아이폰)
- iOS 17부터 **전체 접근**(`requestFullAccessToEvents`)이 필요하다. 쓰기 전용 권한으로는 일정을 읽을 수 없다. `NSCalendarsFullAccessUsageDescription`을 넣는다.
- 일정별 색은 EventKit에 없고 **캘린더 색만** 있다. 그래서 분류는 별도 캘린더로 한다.
- 캘린더마다 `allowsContentModifications`로 쓰기 가능 여부를 확인한다.
- Google 계정은 CalDAV 소스로 보인다. 가져오기 주기는 iOS 설정이 정한다. 앱에서는 `refreshSourcesIfNecessary`만 호출할 수 있다.
- **빌드·설치:** macOS + Xcode가 필요하다. 이 클라우드 컨테이너(Linux)에서는 iOS를 빌드할 수 없으니 GitHub Actions macOS 러너나 사용자의 Mac을 쓴다. 무료 개인 팀 서명은 7일마다 다시 설치해야 한다. Apple Developer Program(연 $99)에 가입하면 TestFlight로 1년 단위 배포가 된다.
- 위젯은 WidgetKit(SwiftUI)이라 이 부분만 Swift로 작성한다.

### 공통
- 반복 일정은 "이번 일정만 / 이후 일정 / 전체" 편집을 지원한다. Android는 예외 인스턴스, iOS는 `EKSpan`을 쓴다.
- 알림은 OS·Google 알림을 그대로 쓴다. 앱이 따로 알림을 띄우면 중복된다.
- 음력은 앱이 계산해서 표시만 한다. 공휴일은 Google의 "대한민국의 휴일" 캘린더를 구독해 쓴다.

---

## 6. 분류(개인·업무·건강) 결정

**분류 = 별도 Google 캘린더**로 정할 것을 제안한다.
- 아이폰은 일정별 색이 없어서 Google 일정 색으로 나누면 아이폰에서 분류가 보이지 않는다.
- 앱 전용 정보로 나누면 삼성·아이폰 캘린더에서 보이지 않는다.
- 별도 캘린더로 나누면 세 앱 모두에서 같은 분류로 보이고, 켜고 끌 수도 있다.

---

## 7. 단계별 진행

| 단계 | 내용 | 결과물 |
|------|------|--------|
| 0 | OLO-Design에 캘린더 확장 등록: 런처 마크, 캘린더 hue 매핑, 날짜 칸·칩 규칙 | OLO-Design 커밋, 앱 세션에 공지 |
| 1 | KMP 골격, `AndroidCalendarStore`, 월·목록·편집, OLO 테마 | 갤럭시용 APK, 실기기 검증 |
| 2 | `IosCalendarStore`, macOS CI 빌드, 서명 | 아이폰 설치본(개인 서명 또는 TestFlight) |
| 3 | 위젯, 주·일 보기, 음력·공휴일, 반복 일정 편집, 사운드 | 일상 사용 버전 |
| 4(선택) | PC 웹: Calendar API + OAuth(Production 전환), 증분 동기화(syncToken, 410 처리) | 필요할 때만 |

## 8. 완료 기준
- 갤럭시의 OLO에서 만든 일정이 삼성 캘린더에 바로 보이고, Google과 아이폰 캘린더·아이폰 OLO에도 보인다.
- 아이폰 캘린더에서 고친 일정이 갤럭시 OLO·삼성 캘린더에 반영된다. 반대 방향도 같다.
- 삭제가 반영되고 중복 일정이 생기지 않는다.
- `이 기기만`·iCloud 캘린더에 저장하려 하면 동기화되지 않는다고 미리 알린다.
- 권한 거부나 계정 동기화가 꺼진 경우 이유와 해결 방법을 보여 준다.
- 종일·여러 날·반복·시간대 일정이 세 앱에서 같게 보인다.

## 9. 사용자 결정이 필요한 것
1. Apple Developer Program 가입 여부(연 $99). 미가입이면 7일마다 재설치.
2. iOS 빌드 환경: 개인 Mac / GitHub Actions macOS 러너.
3. 분류 = 별도 Google 캘린더 안에 동의하는지.
4. PC 웹(4단계)이 필요한지, 아니면 Google 캘린더 웹으로 충분한지.
5. 런처 마크 A / B 선택.

## 출처
- Android Calendar Provider: https://developer.android.com/identity/providers/calendar-provider
- Apple TN3152 캘린더 접근 수준: https://developer.apple.com/documentation/technotes/tn3152-migrating-to-the-latest-calendar-access-levels
- EventKit 저장소 접근: https://developer.apple.com/documentation/eventkit/accessing-the-event-store
- Compose Multiplatform 1.8.0(iOS Stable): https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/
- OAuth 범위(Calendar): https://developers.google.com/identity/protocols/oauth2/scopes
- Sensitive scope 검토: https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification
- Testing → Production refresh token 만료: https://tech.sailjada.com/posts/2026-05-06-1924.html
- Calendar API 푸시 알림: https://developers.google.com/workspace/calendar/api/guides/push
- Calendar API 증분 동기화·410: https://cli.nylas.com/guides/google-calendar-api-sync-tokens
- 무료 개인 팀 7일 만료: https://developer.apple.com/forums/thread/712540
- OLO 세계관: https://github.com/kgcaudit/olo-design (`docs/DESIGN-BRIEF.md`, `tokens/*`, `appicons/README.md`)
