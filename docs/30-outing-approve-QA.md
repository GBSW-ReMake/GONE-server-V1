# #30 외출증 승인 API — 코드 리뷰 & QA 결과

기획서: [30-outing-approve.md](./30-outing-approve.md)

## 코드 리뷰 (9단계, 별도 에이전트 + `code-review` 스킬)
구현한 세션이 아니라 새로 띄운 에이전트가 `dev...HEAD` diff와 기획서를 보고 독립적으로
리뷰했다. 발견된 문제 1건은 실질 영향이 낮아 즉시 코드 수정은 하지 않고, 아래 QA 결과와
함께 Low로 정리했다(자세한 내용은 "발견된 문제" 절 참고).

리뷰에서 "문제 아님"으로 확인된 항목:
- `@EnableMethodSecurity` 활성화가 기존 코드에 미치는 영향 — 전체 코드베이스에
  `@PreAuthorize`/`@PostAuthorize`/`@Secured` 사용처가 이번에 추가한 것 하나뿐임을 재확인,
  잠자던 애노테이션이 갑자기 활성화되는 일 없음.
- `AccessDeniedException` 핸들러의 다른 핸들러와의 충돌/가림 가능성 — 상속 관계가 없어
  Spring의 최적 매칭 규칙상 문제 없음.
- `OUTING_004`/`005`/`006`이 기존 에러 코드와 의미/상태코드 충돌 없음, `CommonErrorCode.
  FORBIDDEN` 재사용도 기존에 진짜 안 쓰이고 있었음 재확인.
- 소유권 체크 로직(`outing.getTeacher().getId().equals(teacherUserId)`), `toResponse` 내
  지연 로딩(`getStudent()`/`getTeacher()`)이 트랜잭션 안에서만 접근돼 안전함, `id → code`
  변경이 빠짐없이(엔티티/서비스/컨트롤러/테스트/문서) 전부 반영됐음을 확인.

## QA (10단계)
### 정적 검증
- `./gradlew build`(checkstyleMain/checkstyleTest/test 전체 포함) — **통과**
- 신규/보강 테스트: `OutingServiceTest.ApproveOuting`(정상 승인/404/403/409, 4건),
  `OutingControllerTest.ApproveOuting`(principal 전달 검증, 1건),
  `OutingApproveAuthorizationTest`(이 프로젝트 첫 전체 컨텍스트 + 실제 보안 필터 체인 통합
  테스트 — 무인증 401, STUDENT 역할 403, 2건) — 전부 통과

### 실제 서버 기동 검증 — 인증된 실사용자 happy path 포함 전부 확인
`./gradlew bootRun --args='--spring.profiles.active=dev'`로 실제 기동 후, #32 QA 때 정립한
raw JDBC 시드 방식(`jshell` + `mysql-connector-j`)으로 학생 1명 + 담당 선생님 1명 + 담당
아닌 다른 선생님 1명 계정을 만들어 전체 플로우를 실제로 검증했다.

1. 학생 계정으로 로그인 → `POST /api/v1/outings`로 외출증 신청(`CUSTOM` 19:00~20:00) → 정상
   접수, 응답 `code` 필드 정상 노출(`id → code` 변경 실제 동작 확인)
2. **담당 아닌 다른 선생님**이 승인 시도 → `403 OUTING_004` 정상
3. **STUDENT 역할**로 승인 시도 → `403 COMMON_003` 정상(`@PreAuthorize`가 서비스 로직 전에
   실제로 막음)
4. **토큰 없이** 승인 시도 → `401 COMMON_002` 정상
5. **존재하지 않는 code**로 승인 시도 → `404 OUTING_006` 정상
6. **담당 선생님**이 정상 승인 → `200`, 응답 `status: APPROVED` 정상
7. 같은 선생님이 **이미 승인된 건을 다시** 승인 시도 → `409 OUTING_005` 정상(멱등 처리 안 함,
   기획서에서 확정한 대로)
8. 검증 후 테스트 계정/외출증(`user`/`user_role`/`gbsw`/`outing` 각 1~3건)을 모두 삭제,
   기동했던 프로세스도 종료 처리함

**QA 중 발견한 해프닝(서버 버그 아님)**: 처음 외출증 신청 요청에서 `curl -d` 인자에 한글
텍스트를 직접 넣었더니 터미널 인코딩 문제로 `HttpMessageNotReadableException: Invalid UTF-8
middle byte`가 발생, `500`으로 응답됐다. 요청 바디를 파일로 먼저 만들어(UTF-8 보장) `--data-
binary @file`로 보내니 정상 동작함을 확인 — 클라이언트(테스트 스크립트) 쪽 문제였고 서버
로직과는 무관하다.

## 발견된 문제 (심각도별)

**Low**
- 마스터 기획서(`docs/outing-domain.md`)가 "확정"으로 명시한 문구 — "나머지(승인/거절/출발/
  도착)는 상태 전이 조건 자체가 자연스러운 락 역할을 한다(같은 상태에서만 다음 단계로 갈 수
  있으므로 두 번째 시도는 자동으로 막힘)" — 가 `approveOuting`에는 실제로 보장되지 않는다.
  비관적 락(`applyOuting`이 쓰는 `findByIdForUpdate` 같은)이나 낙관적 락(`@Version`) 없이
  `status == PENDING` 체크만 하므로, 이론적으로는 같은 선생님의 동시 이중 클릭 시 두 요청
  모두 체크를 통과해 둘 다 `APPROVED`로 쓸 수 있다(check-then-act 레이스).
  - **실사용 영향은 낮다**: 소유권 체크가 이미 있어 다른 사람이 끼어들 수 없고, 결과 상태값
    자체는 동일(둘 다 `APPROVED`)하므로 데이터가 깨지지는 않는다 — `approvedAt`이 둘 중
    나중에 커밋된 값으로 덮어써지는 정도의 영향.
  - 지금 당장 고치기보다는, 이 인지 자체를 문서에 남긴다: **#31(거절) 등 후속 이슈에서
    "#30과 동일 패턴"을 그대로 따르면 안 되고, 이 레이스가 실제로 문제가 되는지(예:
    `rejected_reason`처럼 매번 다른 값을 쓰는 필드가 있으면 영향이 커짐) 다시 판단해야
    한다.**

## 완료 조건 확인
- [x] 로컬 빌드/테스트 통과 (`./gradlew build`)
- [ ] CI 통과 — PR 생성 후 확인 필요
