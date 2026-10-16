# OLO Calendar

갤럭시·아이폰 공용 OLO 가족 캘린더 앱. 휴대폰의 캘린더 저장소(삼성 캘린더·아이폰 캘린더와 같은 곳)를 그대로 읽고 쓰며, Google과의 동기화는 OS가 맡는다. 디자인 기준은 [OLO-Design](https://github.com/kgcaudit/olo-design).

## 문서
- [구상안](docs/OLO-Calendar-구상안.md)
- [갤럭시·아이폰·Google 동기화 구조 조사](docs/01-동기화-구조-조사.md)
- [필수 기능 선정](docs/02-필수기능-선정.md)

## 구조
Kotlin Multiplatform + Compose Multiplatform. 한 모듈(`composeApp`)에 공용 코드와 플랫폼 코드가 있다.

| 경로 | 내용 |
|------|------|
| `commonMain/.../design` | OLO 토큰(색·모양·글자), 캘린더 색 → OLO hue 매핑 |
| `commonMain/.../model` | 일정 모델, 반복 규칙(RRULE) 변환, 월 격자, 종일 일정 시각 규칙, 계정 분류 |
| `commonMain/.../store` | `CalendarStore` — OS 캘린더 저장소 인터페이스 |
| `commonMain/.../ui` | 월 보기, 날짜별 목록, 일정 상세·편집, 캘린더·동기화 상태 |
| `androidMain` | `AndroidCalendarStore`(CalendarContract), 권한, 진입점 |
| `iosMain` | 진입점. EventKit 저장소는 2단계 |
| `desktopTest` | 에뮬레이터 없이 화면을 PNG로 렌더링하는 확인용 테스트 |

## 빌드
```sh
./gradlew :composeApp:assembleRelease      # APK (현재는 debug 키로 서명)
./gradlew :composeApp:testDebugUnitTest    # 공용·Android 단위 테스트
./gradlew :composeApp:desktopTest          # 화면 렌더링 → composeApp/build/screens/*.png
```
Android SDK 경로는 `local.properties`의 `sdk.dir`. iOS 타깃은 macOS(Xcode)에서만 빌드된다.

## 런처 아이콘
OLO 가족 규칙상 마크는 OLO-Design이 소유한다. 그쪽에 캘린더 마크가 들어오기 전까지는 `tools/provisional_launcher.py`가 OLO-Design의 붓 도구와 공용 Clay 바탕으로 임시 마크를 만든다.
