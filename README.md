# GAMELOG — 회원 인증과 글 저장·조회

[기능별 상세 구현 계획](https://app.notion.com/p/416bcc84e50e8141854df4b416ab4bc5)의 **3번 → 1단계 회원가입·로그인과 권한**, **2단계를 위한 글 저장 기반과 로그인 후 목록·상세 조회** 구현입니다. React + TypeScript, Spring Boot + Java 21, Spring Security, JPA, Flyway를 사용합니다.

## 로컬 실행

필수: JDK 21, Node.js 22.12 이상. Maven은 `backend/mvnw.cmd` 또는 `backend/mvnw`가 내려받습니다. Windows에서 Java 바로가기가 다른 JRE를 가리킨다면 `JAVA_HOME`을 실제 JDK 21 경로로 설정하세요.

PowerShell 터미널 1 (데모 글 3건을 실제 서버 DB에 준비):

```powershell
cd backend
.\run-demo.ps1
```

실행 스크립트가 JDK 21을 선택하고, 현재 터미널에 `JWT_SECRET`이 없으면 난수 키를 생성합니다. 키 값은 출력하거나 파일에 저장하지 않습니다. 데모 프로필로 실행하며 전체 서버 로그는 `.cache/backend-demo-startup.log`에 저장합니다. 다음과 같이 직접 실행할 수도 있습니다. 환경변수 설정은 **새 터미널마다** 필요합니다.

PowerShell 7에서 직접 실행:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:JWT_SECRET = $bytes = New-Object Byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes); $env:JWT_SECRET = [Convert]::ToBase64String($bytes)
cd backend
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=demo'
```

PowerShell 터미널 2:

```powershell
cd frontend
npm ci
npm run dev
```

[http://127.0.0.1:5173](http://127.0.0.1:5173)에서 **회원가입 → 로그인 → 데모 글 목록 → 제목 선택 → 본문 확인 → 목록으로 돌아가기**를 실행합니다. 제목 검색과 게임 필터를 사용할 수 있으며 회원 메뉴의 ‘내 계정’에서 기존 인증·관리자 접근 확인도 가능합니다. Vite가 `/api`를 8080 포트로 전달하므로 CORS 허용 설정 없이 같은 출처로 요청합니다.

기본 DB는 `backend/data/gamenews.mv.db` 파일에 보존되며, 시작 시 Flyway가 `users`, `games`, `sources`, `articles` 테이블을 생성합니다. 기존 V1 DB는 V2 마이그레이션으로 확장됩니다. 파일 DB 경로는 **backend 디렉터리에서 실행**할 때를 기준으로 합니다. JWT 키를 새로 생성하면 이전 토큰은 무효화되지만 계정과 글은 유지됩니다. 운영에서는 동일한 비밀키를 안전한 환경변수/비밀 저장소로 주입하세요. `.env.example`은 설명용이며 자동 로드하지 않습니다.

데모 프로필은 게임별 예시 글 3건을 출처·외부 ID 기준으로 한 번만 저장합니다. 서버를 재시작해도 중복되지 않고 기존 내용·숨김 상태를 덮어쓰지 않습니다. 글과 화면에는 **데모**를 표시하며 실제 Steam 기사 수집 성공을 의미하지 않습니다. 새 DB에 예시 데이터를 넣지 않으려면 프로필 인자 없이 실행하세요. 프로필을 끄더라도 이미 DB에 저장한 글은 유지됩니다.

같은 파일 DB를 사용하는 API 서버는 하나만 실행하세요. `Process terminated with exit code: 1` 위쪽 로그에 `The file is locked`가 있으면 기존 API 서버가 DB를 사용 중입니다. 기존 서버를 실행한 터미널에서 `Ctrl+C`로 종료한 뒤 다시 실행하세요. 종료 후에도 계정과 글은 파일 DB에 유지됩니다.

`Could not resolve placeholder 'JWT_SECRET'`이면 JWT 키가 없는 터미널에서 실행한 것입니다. 같은 터미널에서 키를 설정하거나 `run-demo.ps1`을 사용하세요. `.env.example`은 예시 파일이며 `.env` 파일을 만들어도 자동으로 로드하지 않습니다. 운영 실행에서는 계속 `JWT_SECRET`을 명시적으로 설정해야 합니다.

서버 내부 `ArticleStore`가 기본 검증·중복 방지·자동 공개 저장을 담당합니다. 일반 회원의 글 작성 API와 관리자 수동 작성 화면은 추가하지 않았습니다. 다음 수집 모듈이 이 저장 경로를 호출하도록 연결할 수 있습니다. 수집 예약 실행·Steam 실호출·수집 이력은 후속 작업입니다. API와 데모 확인 절차는 [docs/articles.md](docs/articles.md)를 참고하세요.

## 관리자 계정

일반 가입에는 역할 입력이 없습니다. 최초 관리자 생성은 신뢰할 수 있는 서버 실행 환경에서만 다음 환경변수를 **둘 다** 설정한 뒤 백엔드를 시작합니다. 비밀번호는 공개 저장소나 이 문서에 기록하지 마세요.

```powershell
$env:ADMIN_EMAIL = 'your-admin@example.com'
$adminPassword = Read-Host '초기 관리자 비밀번호' -AsSecureString
$env:ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $adminPassword).Password
.\mvnw.cmd spring-boot:run
```

생성 후 서버를 종료하고 `Remove-Item Env:ADMIN_EMAIL,Env:ADMIN_PASSWORD`로 설정을 제거한 뒤 다시 시작하세요. 이미 존재하는 ADMIN은 유지하며 비밀번호를 덮어쓰지 않습니다. 기존 USER를 같은 이메일의 ADMIN으로 승격하지 않고 시작을 거절합니다. 관리자도 같은 로그인 폼을 사용하며 로그인 후 관리자 페이지에서 서버 권한 검사를 실행할 수 있습니다.

## 인증 정책

- 이메일 앞뒤 공백 제거, 전체 소문자 변환 후 DB UNIQUE 적용. 동시 가입도 하나만 성공합니다.
- 비밀번호는 8자 이상, UTF-8 72바이트 이하. 공백만 있는 값은 거절하며 비밀번호 자체는 공백 제거·대소문자 변환을 하지 않습니다. BCrypt cost 12로 해시하고 응답에 해시를 포함하지 않습니다.
- 가입 DTO는 이메일·비밀번호만 허용합니다. `role`, `status` 등 알 수 없는 필드를 보내면 400이며 계정을 만들지 않습니다.
- JWT는 HS256만 허용하고 서명, `exp`, `iat`, `iss`, `aud`, `sub`, 선택적 `nbf`를 검증합니다. 유효기간 30분, 시계 오차 유예 0초. `JWT_SECRET`은 Base64 인코딩한 난수 32바이트 이상이어야 하며 누락·짧은 키로는 시작하지 않습니다.
- 모든 보호 요청은 현재 DB의 role/status를 조회합니다. JWT의 role 주장을 신뢰하지 않으므로 기존 토큰도 회원 정지·관리자 권한 회수 직후 거절됩니다.
- 브라우저 토큰은 React 메모리에만 저장합니다. localStorage·sessionStorage·쿠키를 사용하지 않습니다. 새로고침·만료·로그아웃 시 재로그인해야 합니다. 진행 중 요청은 화면 이동 시 취소하고 늦게 도착한 응답으로 로그인이 복원되지 않게 합니다.
- 로그아웃은 현재 클라이언트 토큰 제거입니다. 유출 토큰을 서버에서 즉시 폐기하는 블랙리스트, refresh token, 소셜 로그인, 이메일 인증, 비밀번호 재설정은 이 단계 범위에 없습니다.

## API

요청·응답 예시 및 오류는 [docs/auth-api.md](docs/auth-api.md)에 있습니다.

| 메서드 | 경로 | 권한 | 성공 |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | 공개 | 201 회원 정보 |
| POST | `/api/auth/login` | 공개 | 200 토큰·만료 시각·회원 정보 |
| GET | `/api/me` | 활성 회원 | 200 현재 회원 정보 |
| GET | `/api/admin/access` | 활성 관리자 | 200 권한 확인 메시지 |
| GET | `/api/health` | 공개 | 200 프로세스 상태 |
| GET | `/api/articles?page=&size=&gameId=&q=` | 활성 회원 | 200 공개 글 목록 |
| GET | `/api/articles/{id}` | 활성 회원 | 200 공개 글 상세 |
| GET | `/api/games` | 활성 회원 | 200 게임 목록 |

`/api/admin/access`는 1단계 서버 권한 확인용 API입니다. 신고·회원 관리 기능은 아직 포함하지 않습니다. 회원 정지·권한 변경은 이후 관리자 기능에서 연결할 수 있게 엔티티와 요청 시 검사를 마련했습니다.

## 검증

```powershell
cd backend
.\mvnw.cmd test
cd ../frontend
npm test
npm run build
```

데모 프로필로 서버 두 개가 실행 중일 때 프로젝트 루트에서 `node scripts/http-smoke.mjs --expect-demo`를 실행하면 실제 Vite 프록시를 통한 가입·로그인·인증·권한 거절과 데모 3건의 목록·상세·게임 필터를 검증합니다. 실행마다 로컬 DB에 임의 이메일의 테스트 계정 1개가 생성됩니다. 데모가 없는 환경은 `--expect-demo` 없이 실행할 수 있습니다.

글 테스트는 공개/숨김 직접 접근, 미분류, 검색 특수문자, 안정된 페이지 정렬, 중복·동시 저장, 데모 재실행, 정지 회원 거절을 포함합니다. 화면 테스트는 로그인 직후 글 목록, 상세 열람, 검색·필터·페이지 이동, 빈 결과, 통신 오류·재시도, 인증 만료, 외부 링크 검사와 일반 텍스트 본문 표시를 확인합니다.

백엔드 테스트는 실제 Spring Security 필터·서명 JWT·JPA·Flyway·H2 DB로 HTTP 요청을 검증합니다. 가입/해시 저장, 동시·중복 이메일, 관리자 주입, 입력 오류, 자격 불일치, 미인증, 만료·변조·다른 알고리즘·잘못된 issuer/audience·필수 claim 누락, 관리자 권한 회수, 정지 전 JWT, 삭제 계정을 포함합니다. 프런트엔드는 가입 후 로그인 안내, 인증 헤더, 로그아웃·새로고침·만료, 관리자 거절·정지·통신 실패·늦은 응답을 검증합니다. UI 테스트의 API 응답은 모의 응답입니다.

## PostgreSQL 및 배포

`DB_URL=jdbc:postgresql://호스트:5432/gamenews`, `DB_USERNAME`, `DB_PASSWORD`를 설정하면 PostgreSQL을 사용합니다. 운영 DB는 별도로 생성하고 계정 권한을 부여해야 합니다. 마이그레이션은 H2 PostgreSQL 모드에서 검증했으며 실제 PostgreSQL 및 JCloud 배포는 아직 검증하지 않았습니다.

배포 시 `npm run build` 결과인 `frontend/dist`를 웹서버에서 제공하고 `/api`를 Spring Boot로 프록시하세요. 백엔드는 `./mvnw package` 후 `java -jar target/game-news-api-0.1.0.jar`로 실행합니다. 운영 인증 통신에는 HTTPS가 필요합니다. 현재 스코프에서는 개발 프록시로 연동하며 JCloud 구축은 별도 단계입니다.

JWT 구성 참고: [Spring Security 공식 문서](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
