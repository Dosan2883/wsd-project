# 2단계 — Steam 뉴스 자동 수집·공개

기준: [개정 계획의 3번 → 2단계](https://app.notion.com/p/41cbcc84e50e81cc9f17d4c607067235). 공식 공지를 정제·저장해 관리자 승인 없이 `PUBLISHED`로 공개합니다.

## 실행·확인

기존 API 서버를 `Ctrl+C`로 종료하고 `backend`에서 실행합니다.

```powershell
.\run-steam.ps1
```

스크립트가 JDK 21과 JWT 키를 준비합니다. 기동 약 10초 후 첫 수집, 이후 이전 실행 종료부터 30분 간격입니다. 로그는 `.cache/backend-steam-startup.log`입니다. 직접 실행은 환경변수를 준비하고 `spring-boot:run '-Dspring-boot.run.profiles=steam'`을 사용합니다.

`frontend`에서 `npm run dev`를 실행하고 로그인하면 목록·상세에서 실제 뉴스의 평문 발췌·본문·썸네일·원문 링크를 확인합니다. 관리자는 회원 메뉴의 **수집 관리 → 지금 수집하기**에서 즉시 실행하고 건수·시각·오류 요약을 확인합니다. 관리자 초기 생성은 README의 환경변수 절차를 사용합니다.

데모 생성 기능과 실행 프로필은 제거했습니다. V5는 기존 데모 글과 전용 출처를 정리합니다. 실제 뉴스·계정과 실제 기사가 연결된 게임은 보존합니다. 비회원 뉴스 열람 변경은 개정 계획의 3단계입니다.

## 수집 대상과 정제

| 게임 | Steam AppID |
| --- | --- |
| Counter-Strike 2 | 730 |
| Dota 2 | 570 |
| PUBG: BATTLEGROUNDS | 578080 |
| ELDEN RING | 1245620 |
| Baldur's Gate 3 | 1086940 |

고정된 [Steam GetNewsForApp/v2 공식 API](https://partner.steamgames.com/doc/webapi/ISteamNews)에 `feeds=steam_community_announcements`, `maxlength=0`, 기본 `count=20`을 전달합니다. 퍼블리셔 전용 `GetNewsForAppAuthed`를 사용하지 않습니다.

HTML은 jsoup으로 파싱하고 스크립트·삽입 객체·BBCode를 제거합니다. 평문 본문은 최대 100,000자, 저장 발췌는 최대 250 유니코드 문자입니다. 긴 본문만 생략 부호를 포함해 250자로 자릅니다. 첫 유효한 Steam 이미지를 썸네일로 사용하며 `{STEAM_CLAN_IMAGE}/...`는 `https://clan.fastly.steamstatic.com/images/...`로 바꿉니다. HTTPS Steam 이미지 호스트만 허용하고 이미지가 없거나 로드에 실패하면 숨깁니다.

외부 ID는 문자열로 보존합니다. 게임 번호·발행 시각·공식 피드·필수값·원문 주소를 검사하고 잘못된 항목은 실패로 기록한 뒤 계속 처리합니다.

## 중복·장애·실행 이력

외부 통신은 DB 트랜잭션 밖에서 수행합니다. 각 저장은 짧은 트랜잭션과 `(source_id, external_key)` 유일 제약을 사용합니다. 기존 기사 내용·숨김·게임 정정 상태를 덮어쓰지 않습니다. 기사 `@Version` 충돌은 409로 변환합니다.

기본 요청 제한은 본문 수신까지 10초, 최대 재시도 2회, 응답 상한 4MiB입니다. 연결 실패·시간 초과·429·5xx만 재시도합니다. 다른 4xx나 깨진 JSON은 반복하지 않습니다. `Retry-After`를 반영하며 30초를 넘는 대기는 실패 이력으로 남깁니다. 같은 프로세스는 안내 시각까지 다른 게임의 후속 요청도 보내지 않습니다.

출처별 DB 실행권으로 예약·즉시 수집과 여러 서버의 중복 실행을 막습니다. 실행권은 10분이며 게임 수신·항목 저장 중 갱신합니다. 프로세스 종료로 남은 실행권은 만료 후 회수하고 기존 `RUNNING` 이력을 실패로 종결합니다. 다른 소유자의 실행권은 해제하지 않습니다. DB 자체 장애로 이력 저장이 실패한 경우 다음 정상 실행에서 정리합니다.

게임 호출 실패는 실패 1건, 잘못된 항목도 각각 실패 1건입니다. 호출 실패는 수신 수에 포함되지 않을 수 있습니다. `SUCCESS`는 오류 없음, `PARTIAL`은 정상 처리와 오류 공존, `FAILED`는 정상 처리된 기사 없이 실패한 실행입니다. 정상 빈 피드는 성공 0건입니다.

## 관리자 API·설정

활성 관리자 JWT가 필요합니다. 비회원 401, 일반 회원·정지 계정 403입니다.

| 메서드 | 경로 | 결과 |
| --- | --- | --- |
| POST | `/api/admin/ingestions` | 5개 게임 동기 수집 후 실행 결과 200 |
| GET | `/api/admin/ingestions?page=0&size=10` | 이력·페이지·활성화 설정·예약 간격 200 |

중복 실행은 409 `INGESTION_RUNNING`, 수집 비활성화는 503 `INGESTION_DISABLED`, 출처 비활성화는 503 `SOURCE_DISABLED`입니다. 페이지 0–1,000,000, 크기 1–50입니다. API 시각은 UTC ISO 8601이며 화면은 한국 시간입니다.

V4는 출처 유형·주소·활성 상태·실행권, 기사 발췌·썸네일·버전, `ingestion_runs`를 추가합니다. 기존 `external_id`를 `external_key`로 바꾸면서 유일 제약과 데이터를 보존합니다. 기존 글의 발췌는 본문 앞부분입니다. IGDB/가격 ID는 후속 연동용 nullable 컬럼입니다.

`STEAM_NEWS_COUNT`는 게임당 1–100건(기본 20), `STEAM_SCHEDULING_ENABLED=false`는 예약 실행만 끕니다. `steam` 프로필은 실제 수집을 활성화합니다. 전체 수집을 끄려면 기본 프로필에서 `STEAM_INGESTION_ENABLED=false`로 실행합니다. 세부값은 `ingestion.steam.*`로 조정합니다.

## 검증

기본 테스트는 외부 네트워크 없이 실제 응답 자료·로컬 HTTP 서버로 정제·재시도·시간 제한·응답 상한·중복·동시 실행·권한·이관을 검증합니다. 원본 JSON 5개와 출처·수집 시각은 `backend/src/test/resources/fixtures/steam/`입니다. 화면 테스트는 모의 API 응답을 사용합니다.

직접 Steam에 접속하는 검증은 명시적으로 켭니다. 사용자 DB 대신 `.cache/steam-live-verification.mv.db`에 저장하며 종료 시 연결을 닫습니다.

```powershell
cd backend
$env:STEAM_LIVE_TEST = 'true'
.\mvnw.cmd '-Dtest=LiveSteamIngestionTest' test
Remove-Item Env:STEAM_LIVE_TEST
```

2026-11-19 실제 호출 결과: 게임 5종, 첫 실행 수신·신규 25건, 두 번째 신규 0건·중복 25건, 실패 0건. 이후 새 공지가 생기면 응답은 달라집니다. PostgreSQL·JCloud 운영 검증은 배포 단계에 남아 있습니다.
