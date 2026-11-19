# 인증 API — 1단계

출처: [Notion 3번의 1단계](https://app.notion.com/p/416bcc84e50e8141854df4b416ab4bc5). JSON 요청에 `Content-Type: application/json`을 사용합니다. 시간은 UTC ISO 8601이며 화면에서는 한국 시간으로 표시합니다.

## 가입

`POST /api/auth/signup`

```json
{"email":"member@example.com","password":"your-own-password"}
```

성공 201. 자동 로그인하지 않으며 로그인 폼으로 이동합니다.

```json
{"id":1,"email":"member@example.com","role":"USER","status":"ACTIVE","createdAt":"2026-11-12T05:00:00Z"}
```

이메일은 앞뒤 공백 제거 후 전체 소문자로 정규화합니다. 254자 이하의 이메일 형식, 비밀번호 8자 이상/UTF-8 72바이트 이하를 서버에서 검사합니다. 이메일 중복은 409 `EMAIL_ALREADY_EXISTS`, 입력 오류는 400입니다. `role=ADMIN`과 같은 추가 필드는 400 `INVALID_REQUEST`로 거절합니다.

## 로그인

`POST /api/auth/login`에 가입과 같은 두 필드를 보냅니다.

```json
{"accessToken":"<signed JWT>","tokenType":"Bearer","expiresAt":"2026-11-12T05:30:00Z","user":{"id":1,"email":"member@example.com","role":"USER","status":"ACTIVE","createdAt":"2026-11-12T05:00:00Z"}}
```

200. 알려지지 않은 계정과 잘못된 비밀번호는 동일한 401 `INVALID_CREDENTIALS` 응답입니다. 올바른 자격이라도 정지 회원은 403 `ACCOUNT_SUSPENDED`입니다. 입력 형식이 잘못되면 400입니다.

## 보호 API

`GET /api/me`, `GET /api/admin/access`에 `Authorization: Bearer <accessToken>`을 전달합니다. `me`는 현재 DB의 회원 정보, `admin/access`는 `{"message":"관리자 권한이 확인되었습니다."}`를 반환합니다.

| 상태 | 코드 | 의미 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 필드 오류 (`fields`에 메시지) |
| 400 | `INVALID_PASSWORD` | 바이트 길이/공백 비밀번호 오류 |
| 400 | `INVALID_REQUEST` | 잘못된 JSON·허용하지 않은 필드 |
| 401 | `INVALID_CREDENTIALS` | 잘못된 로그인 자격 |
| 401 | `UNAUTHENTICATED` | 인증 누락·만료·변조·사용자 삭제 |
| 403 | `FORBIDDEN` | 일반 회원의 관리자 접근 |
| 403 | `ACCOUNT_SUSPENDED` | 현재 DB에서 정지된 계정 |
| 409 | `EMAIL_ALREADY_EXISTS` | 정규화 이메일 중복 |

오류 공통 형식:

```json
{"code":"VALIDATION_ERROR","message":"입력 내용을 확인해 주세요.","fields":{"email":"올바른 이메일을 입력해 주세요."}}
```

`/api/me` 및 관리자 접근 시 DB role/status를 다시 읽습니다. 토큰 안에 ADMIN을 넣거나 과거에 관리자였던 토큰을 보내도 현재 DB 권한보다 높게 접근할 수 없습니다. 로그아웃 API는 없으며 화면에서 토큰을 제거합니다. 탈취 토큰은 만료 또는 계정 정지 전까지 유효할 수 있습니다.
