# QA 결과 보고 — 이슈 #89 알리고(Aligo) SMS 연동

관련 기획서: [89-auth-aligo-sms.md](./89-auth-aligo-sms.md)
코드 리뷰 결과: [89-auth-aligo-sms-code-review.md](./89-auth-aligo-sms-code-review.md) (Critical/High/Medium/Low 없음)

- **테스터**: claude-sonnet-5 (자동)
- **테스트 환경**: 로컬 (`localhost:9090`, 로컬 MySQL 3306 / Redis 6379, `dev` 프로필)
- **테스트 일시**: 2026-08-22

---

## 심각도 정의

| 심각도 | 기준 |
|--------|------|
| Critical | 서비스 불가 / 데이터 손상 |
| High | 핵심 기능 동작 불가 |
| Medium | 기능 일부 오작동, 회피 가능, 환경 제약으로 검증 못 한 항목 |
| Low | 사소한 개선점 |

---

## 로컬 빌드/테스트/정적 검사

| 항목 | 결과 |
|------|------|
| `./gradlew checkstyleMain checkstyleTest` | ✓ 통과 (경고 0건) |
| `./gradlew test` (전체) | ✓ 통과 |
| `./gradlew build` (전체) | ✓ 통과 |
| 신규 `AligoSmsSenderTest` (3케이스: 성공/실패응답/네트워크오류) | ✓ 통과 |
| 수정된 `PhoneAuthServiceTest` (import 경로만 변경) | ✓ 통과 (7케이스 회귀 없음) |

## 엔드포인트 정상/에러 케이스 (실서버, `dev` 프로필)

이번 이슈는 신규/변경 엔드포인트가 없다 — 기존 `/api/v1/auth/phone/*`의 구현체 교체가
회귀를 일으키지 않는지 확인하는 것이 QA의 핵심이다.

| 케이스 | 요청 | 기대 | 실제 | 결과 |
|--------|------|------|------|------|
| 인증번호 발송 | `POST /send-code {"phoneNumber":"01099998888"}` | `200`, `expiresIn: 300` | `200`, `expiresIn: 300` | ✓ |
| 콘솔 발송 로그 출력 | 위 요청 직후 서버 콘솔 | `[SMS] 01099998888: [GONE] 인증번호는 NNNNNN 입니다.` | 동일하게 출력됨(`469483`) | ✓ |
| 정상 인증 확인 | `POST /verify-code {phoneNumber, code}` (위에서 받은 코드) | `200`, `ticket` 발급 | `200`, `ticket` 발급 | ✓ |
| 재발송 쿨다운 | 같은 번호로 `send-code` 연속 2회 | 2번째 `429 AUTH_004` | `429 AUTH_004`, `remainingSeconds` 포함 | ✓ |
| 틀린 인증번호 | `POST /verify-code` 잘못된 code | `400 AUTH_001`, `currentFailCount` 증가 | `400 AUTH_001`, `currentFailCount: 1` | ✓ |
| 만료/미발송 번호 검증 | 발송한 적 없는 번호로 `verify-code` | `400 AUTH_002` | `400 AUTH_002` | ✓ |

**핵심 확인 사항**: `SmsSender`/`ConsoleSmsSender`를 `auth.utils` → `sms` 패키지로 옮긴
뒤에도 `dev` 프로필에서 `ConsoleSmsSender` 단독으로 정상 등록·동작한다(`AligoSmsSender`가
`@Profile("!dev")`로 정확히 배제됨 — 두 구현체가 동시에 활성화됐다면 `NoUniqueBeanDefinition
Exception`으로 서버 기동 자체가 실패했을 것이므로, 서버가 정상 기동했다는 사실 자체가
프로필 분기가 의도대로 동작함을 방증한다).

---

## 발견된 문제

### Critical / High

없음.

### Medium

1. **알리고 실제 발송 검증(staging) — 환경 제약으로 미검증**. `AligoSmsSender`가
   `staging`/`prod`에서 실제로 문자를 발송하는지는 로컬에서 검증할 수 없다(실제 알리고
   키·발신번호 사전 등록이 필요하고, 건당 과금이 발생한다 — 기획서 "리스크 및 고려사항"
   절 참고). `AligoSmsSenderTest`가 `MockRestServiceServer`로 요청/응답 형식을 검증하지만,
   이는 알리고 API가 문서와 실제로 동일하게 동작한다는 보장까지는 아니다.
   - **필요 조치**: GitHub Environment "STAGING"에 `ALIGO_KEY`/`ALIGO_USER_ID`/
     `ALIGO_SENDER` 시크릿 등록, 알리고 관리자 페이지에 발신번호 사전 등록 — 둘 다 보스
     소관이라 QA에서 직접 처리할 수 없다. staging 배포 후 실제 발송 1~2건으로 최종 확인이
     필요하다.
2. **GitHub Actions CI 파이프라인 미확인**. `.github/workflows/ci.yml`은 `pull_request`
   (대상: main/dev/staging)와 `push`(대상: main/dev/staging)에서만 트리거되고, `dev`/
   `staging` 직접 push는 워크플로우 규칙상 금지돼 있어 PR을 열기 전에는 실제 GitHub
   Actions를 실행할 방법이 없다. 대신 CI가 실행하는 것과 동일한 명령
   (`checkstyleMain`/`checkstyleTest`/`build -x checkstyleMain -x checkstyleTest`)을
   로컬에서 그대로 실행해 통과를 확인했다 — `contextLoads()`를 포함한 전체 `test` 태스크가
   `./gradlew build`에 포함되어 로컬 MySQL/Redis로 통과했다. 실제 CI(다른 러너 환경, MySQL/
   Redis 컨테이너 헬스체크)는 PR 생성(16단계) 직후 확인이 필요하다.

### Low

없음.

---

## 종합 판정

**Critical/High 없음 — PR 진행 가능.** Medium 2건은 이 이슈의 구현 자체 문제가 아니라
QA 환경의 구조적 제약(실제 알리고 키는 보스 소관, GitHub Actions는 PR 없이 트리거 불가)이라
PR 생성을 막을 사유는 아니다. 다만 PR 생성 직후 실제 CI 결과 확인과, staging 배포 후
`ALIGO_*` 시크릿 등록 + 실제 발송 확인이 후속 조치로 반드시 필요하다.
