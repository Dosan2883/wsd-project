# 글 저장·조회와 데모 확인

## 실행과 확인 순서

1. `backend`에서 `.\run-demo.ps1`로 API를 실행합니다. JDK 21과 로컬 데모용 JWT 키를 준비하고 데모 프로필을 활성화합니다. 직접 실행하려면 README의 환경변수를 같은 터미널에 설정한 뒤 `.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=demo'`를 사용합니다. JAR 실행은 `java -jar target/game-news-api-0.1.0.jar --spring.profiles.active=demo`이며 이때도 환경변수 설정이 필요합니다.
2. `frontend`에서 `npm run dev`를 실행하고 [로컬 화면](http://127.0.0.1:5173)을 엽니다.
3. 사용할 이메일·비밀번호로 회원가입한 뒤 로그인합니다. 기존 회원도 그대로 로그인할 수 있습니다.
4. 로그인 직후 글 목록에 Dota 2, Counter-Strike 2, Team Fortress 2의 **데모 글 3건**이 표시됩니다.
5. 제목을 선택하면 서버에서 상세 본문·게임·출처·발행 시각을 읽어 표시합니다. 시간은 한국 시간입니다. 데모의 외부 링크는 해당 게임의 Steam 상점 페이지이며 기사 원문을 뜻하지 않습니다.
6. ‘글 목록으로’ 돌아가 검색·게임 필터를 확인합니다. 상세에서 돌아와도 검색 조건과 페이지를 유지합니다.
7. API 서버를 종료했다가 같은 DB로 다시 실행하고 글 수와 ID가 유지되는지 확인합니다. 브라우저 새로고침은 현재 탭의 토큰으로 `/api/me`를 호출해 인증을 복원합니다. 토큰이 만료되었거나 서버의 JWT 키가 바뀌었다면 재로그인합니다.

데모는 `demo` 프로필이 명시된 경우에만 생성합니다. 재시작은 예시 콘텐츠를 복구하거나 숨김을 해제하는 작업이 아닙니다. 이미 저장된 데모 글은 프로필을 꺼도 DB에 남습니다. 실제 수집·스케줄러는 이 구현에 포함하지 않습니다.

## 저장 구조

`games`는 내부 ID와 Steam AppID, `sources`는 출처 코드·이름, `articles`는 출처/게임 FK·외부 ID·제목·본문·원문 주소·발행/저장 시각·공개 상태·데모 여부를 저장합니다. 게임 연결이 없으면 미분류입니다. 외부 ID는 숫자 변환 없이 문자열로 저장합니다.

`ArticleStore.store(Draft)`는 서버 내부 수집용 서비스입니다. 입력의 필수값·길이·참조 대상·HTTP(S) 원문 주소를 검사하고 신규 글을 `PUBLISHED`로 저장합니다. `(source_id, external_id)` 유일 제약으로 중복·동시 저장을 막고, 기존 글의 내용·게임 연결·`HIDDEN` 상태를 변경하지 않습니다. 일반 회원용 글 작성 API는 없습니다.

## 조회 API

모든 요청에 `Authorization: Bearer <로그인 토큰>`이 필요합니다. 비회원/유효하지 않은 토큰은 401, 정지 회원은 403입니다.

| 메서드·경로 | 응답 |
| --- | --- |
| `GET /api/articles?page=0&size=20&gameId=1&q=Dota` | `items`, `page`, `size`, `totalElements`, `totalPages` |
| `GET /api/articles/{id}` | `id`, `title`, `body`, `game`, `source`, `originalUrl`, `publishedAt`, `createdAt`, `demo` |
| `GET /api/games` | 내부 ID·게임명·Steam AppID 목록 |

목록 항목은 상세의 본문 대신 `excerpt`를 포함합니다. 검색은 제목 부분 일치이며 대소문자를 구분하지 않고 `%`, `_`, `!`도 실제 문자로 검색합니다. `page`는 0부터, `size`는 1–50(기본 20), `q`는 최대 100자입니다. 프런트엔드는 페이지당 10건을 요청합니다. 발행 시각 내림차순, 같은 시각에서는 내부 ID 내림차순으로 정렬합니다. 잘못된 인자는 400 `INVALID_PARAMETER`, 빈 검색 결과는 200과 빈 `items`입니다.

`PUBLISHED`만 목록·상세에 노출합니다. 숨김 글과 존재하지 않는 글의 상세는 모두 404 `ARTICLE_NOT_FOUND`이며 제목·본문을 반환하지 않습니다. 본문은 일반 텍스트로 표시하므로 HTML을 실행하지 않습니다.

## HTTP 검증과 재시작 확인

API·프런트엔드 서버를 모두 실행한 상태에서 프로젝트 루트에서 다음 명령을 실행합니다. 매번 테스트 회원 1명이 생성됩니다. 비밀번호와 토큰은 출력하거나 파일에 저장하지 않습니다.

```powershell
node scripts/http-smoke.mjs --expect-demo --save-demo-snapshot
```

데모 글 ID만 `.cache/demo-article-ids.json`에 기록합니다. API 서버를 같은 파일 DB로 재시작한 뒤:

```powershell
node scripts/http-smoke.mjs --expect-demo --check-demo-snapshot
```

가입·로그인·권한 검사, 데모 3건의 실제 조회와 필터, 재시작 전후 ID 유지를 함께 확인합니다. 기존 DB에서 데모를 숨긴 상태라면 3건 검증은 실패하는 것이 정상이며 검증 스크립트가 숨김을 해제하지 않습니다.
