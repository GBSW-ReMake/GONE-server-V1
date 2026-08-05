# #20 회원가입 마법사 API 명세 (프론트엔드용)

관련 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/20
백엔드 설계/근거 문서: [20.md](./20.md) (이 문서는 그중 프론트가 실제로 연동할 계약만 뽑아 정리)

## 화면 순서와 API 호출 지점

```
[아이디] → [비밀번호] → [전화번호 인증] → [이름] → [프로필 사진]
                              ↑
                     여기서 계정이 실제로 생성되고
                     로그인 토큰이 발급됨
```

| 화면 | 호출 API | 비고 |
|---|---|---|
| 아이디 | `GET /auth/login-id/check` | 입력할 때마다(디바운스 권장) |
| 비밀번호 | 없음 | 로컬 형식 검증만 |
| 전화번호 인증 | `POST /auth/phone/send-code`<br>`POST /auth/phone/verify-code`<br>**`POST /auth/signup`** | "다음"에서 `signup` 호출. 여기서 토큰 발급됨 → 이후 화면은 전부 인증된 요청 |
| 이름 | `GET /users/me` (진입 시)<br>`PATCH /users/me/name` (변경 시에만) | 기본값 그대로면 API 호출 없이 "다음" |
| 프로필 사진 | `POST /files/profile-image/upload-url`<br>(R2에 직접 PUT)<br>`POST /files/profile-image/confirm` | 건너뛰면 호출 없이 "완료" |

**중요**: "전화번호 인증" 화면에서 `signup`이 성공하면 그 응답에 Access/Refresh Token이 바로
들어있다. 이 시점부터 로그인 상태로 취급하고 토큰을 저장할 것 — 별도로 `/auth/login`을 호출할
필요 없음. 이후 "이름", "프로필 사진" 화면의 모든 요청은 `Authorization: Bearer {accessToken}`
헤더가 필요하다.

**뒤로가기 주의**: 사용자가 "전화번호 인증" 화면으로 뒤로가기 후 "다음"을 다시 누르는 경우, 이미
토큰을 받은 상태라면 `signup`을 재호출하지 말고 그냥 다음 화면으로 넘어갈 것. 재호출하면
`USER_001`(이미 가입됨) 또는 `USER_002`(아이디 중복)로 실패한다.

## 공통 응답 포맷
모든 API는 아래 포맷으로 응답한다.

```json
{
  "success": true,
  "data": { /* 엔드포인트별 실제 데이터, 없으면 null */ },
  "message": "사람이 읽을 안내 메시지",
  "code": null
}
```

실패 시 `success: false`, `code`에 아래 "에러 코드" 표의 값이 들어온다.

```json
{
  "success": false,
  "data": null,
  "message": "이미 사용 중인 아이디입니다.",
  "code": "USER_002"
}
```

---

## 1. 아이디 화면

### `GET /api/v1/auth/login-id/check?loginId={loginId}`
- `loginId`: 영문/숫자 4~20자
- 응답 `data`: `{ "available": true }`

---

## 2. 비밀번호 화면
API 호출 없음. 영문+숫자+특수문자 모두 포함 8~20자(클라이언트에서도 동일 규칙으로 미리 검증
권장): `^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9\s]).{8,20}$`

---

## 3. 전화번호 인증 화면

### `POST /api/v1/auth/phone/send-code`
```json
// Request
{ "phoneNumber": "01012345678" }
```
```json
// Response data
{ "expiresIn": 300 }
```

### `POST /api/v1/auth/phone/verify-code`
```json
// Request
{ "phoneNumber": "01012345678", "code": "123456" }
```
```json
// Response data
{ "ticket": "a1b2c3d4-...", "expiresIn": 600 }
```
- 실패(코드 불일치) 시 `data`에 `{ "currentFailCount": 2, "maxFailCount": 5 }`가 함께 온다 —
  남은 시도 횟수 안내에 사용.

### `POST /api/v1/auth/signup` ⭐ 계정 생성 + 토큰 발급
```json
// Request — 1~3단계에서 모은 값을 그대로 실어 보낸다
{
  "loginId": "testuser01",
  "password": "Test1234!",
  "phoneNumber": "01012345678",
  "ticket": "a1b2c3d4-..."
}
```
```json
// Response data (성공)
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi...",
  "accessTokenExpiresIn": 1800
}
```
- **`name` 필드는 보내지 않는다** — 서버가 학적 정보 기반으로 자동 생성한다(예: `3118정문경`).
- 이 응답을 받으면 **즉시 로그인 상태로 전환**하고 토큰을 저장한다.

---

## 4. 이름 화면

### `GET /api/v1/users/me` (인증 필요)
화면 진입 시 호출해서 현재 이름(자동 생성된 기본값 포함)을 프리필한다.

```json
// Response data
{
  "name": "3118정문경",
  "nameCustomized": false,
  "hasProfileImage": false
}
```
- `nameCustomized: false`면 "이 이름은 자동으로 만들어졌어요, 바꾸시겠어요?" 같은 유도 문구를
  보여줄 수 있다(강제 아님 — 그냥 두고 넘어가도 됨).

### `PATCH /api/v1/users/me/name` (인증 필요, 사용자가 값을 바꿨을 때만 호출)
```json
// Request
{ "name": "새로운별명" }
```
- 성공: `data: null`, 이름이 변경됨.
- 실패(중복, 본인의 현재 이름과 다른데 남이 이미 씀): `409` + `code: "USER_003"`

---

## 5. 프로필 사진 화면

### `POST /api/v1/files/profile-image/upload-url` (인증 필요)
```json
// Request
{
  "fileName": "photo.jpg",
  "contentType": "image/jpeg",
  "fileSize": 204800
}
```
```json
// Response data
{
  "uploadUrl": "https://....r2.cloudflarestorage.com/...(서명된 URL)",
  "key": "profile/42/f47ac10b-....jpg"
}
```
- `contentType`은 `image/jpeg` 또는 `image/png`만 허용, `fileSize`는 5MB(5,242,880) 이하만 허용.
- 다음 단계에서 `PUT` 요청의 `Content-Type`/`Content-Length`가 여기서 보낸 값과 **정확히
  같아야 한다.** 다르면 R2가 서명 불일치로 업로드를 거부한다.

### R2에 직접 업로드
```
PUT {uploadUrl}
Content-Type: image/jpeg
Content-Length: 204800

(이미지 바이너리)
```
- 우리 서버가 아니라 `uploadUrl`(R2)로 직접 보낸다. 인증 헤더 불필요(URL 자체가 서명됨).

### `POST /api/v1/files/profile-image/confirm` (인증 필요)
```json
// Request
{ "key": "profile/42/f47ac10b-....jpg" }
```
- 위 `upload-url` 응답에서 받은 `key`를 그대로 넣는다.
- 성공하면 이 사진이 바로 본인 계정의 프로필 사진으로 저장된다(추가 API 호출 불필요).
- 실패(R2에 실제로 업로드가 안 됨): `400` + `code: "FILE_001"` — PUT이 먼저 성공했는지 확인.

---

## 인증 헤더
"전화번호 인증" 화면 이후 모든 요청에 필요:
```
Authorization: Bearer {accessToken}
```
`accessToken`이 만료되면(`accessTokenExpiresIn`초 후) `POST /api/v1/auth/reissue`에
`refreshToken`을 보내 재발급받는다 — 이 흐름은 기존 로그인/앱 실행 시와 동일.

---

## 에러 코드 참고

| code | HTTP | 상황 | 어느 화면 |
|---|---|---|---|
| `COMMON_001` | 400 | 요청 형식 오류(필드 누락/형식 위반) | 전체 |
| `COMMON_002` | 401 | 인증 필요(토큰 없음/만료) | 이름, 프사 |
| `COMMON_006` | 409 | 동시 요청 경합(드묾, 재시도 유도) | 전화번호 인증(signup) |
| `AUTH_001` | 400 | 인증번호 불일치 | 전화번호 인증 |
| `AUTH_002` | 400 | 인증번호 만료 | 전화번호 인증 |
| `AUTH_003` | 429 | 인증 시도 횟수 초과 | 전화번호 인증 |
| `AUTH_004` | 429 | 인증번호 재발송 과다 | 전화번호 인증 |
| `AUTH_005` | 400 | ticket 없거나 만료 | 전화번호 인증(signup) |
| `AUTH_006` | 400 | ticket의 번호와 signup의 번호 불일치 | 전화번호 인증(signup) |
| `GBSW_001` | 404 | 학교 명단에 없는 번호 | 전화번호 인증(signup) |
| `USER_001` | 409 | 이미 가입된 계정(같은 학적) | 전화번호 인증(signup) |
| `USER_002` | 409 | 아이디 중복 | 아이디, signup |
| `USER_003` | 409 | 별명 중복 | 이름 |
| `FILE_001` | 400 | 업로드 확인 실패(R2에 파일 없음) | 프로필 사진 |
| `FILE_002` | 400 | 지원하지 않는 파일 형식 | 프로필 사진 |
| `FILE_003` | 413 | 파일 크기 초과(5MB) | 프로필 사진 |
