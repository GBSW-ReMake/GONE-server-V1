# #30 외출증 승인 API

관련 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/30
전체 도메인 마스터 기획서: [outing-domain.md](./outing-domain.md) — 이 문서는 그중 "승인"
엔드포인트(2번)만 좁힌 것. 선행 이슈: #29(외출증 신청, 머지 완료).

## 개요/목적
외출증 신청(#29) 이후, 담당 선생님이 그 신청을 승인(사인)하는 API 하나만 구현한다. 거절/
출발/도착 등은 각각 후속 이슈(#31, ...)로 따로 진행한다.

이 프로젝트에서 역할(Role) 기반 선언적 인가(`@PreAuthorize`)를 실제로 처음 쓰는 이슈이기도
하다 — `@EnableMethodSecurity`가 아직 활성화되어 있지 않다(`SecurityConfig` 확인 완료,
기존 `@PreAuthorize`/`@PostAuthorize`/`@Secured` 사용처 전체 검색 결과 없음 — 이번에 처음
켜도 기존 엔드포인트 중 갑자기 활성화되며 깨질 "잠자던" 인가 애노테이션은 없다).

## 엔드포인트

### `PATCH /api/v1/outings/{code}/approve`
- **인증/권한**: `TEACHER` 역할(`@PreAuthorize("hasRole('TEACHER')")`) + 본인이 그 외출증에
  지정된 담당 선생님인지 서비스 레벨 소유권 확인(역할만으로는 부족 — 안 그러면 아무 선생님이나
  남의 반 학생 외출증에 사인할 수 있다. 마스터 기획서 "권한 모델"의 `FileController` 프로필
  이미지 소유권 확인과 같은 패턴)
- **요청**: 바디 없음(경로의 `{code}`만 사용, 승인 행위 자체가 사인)
- **응답** (`200 OK`) — #29 신청 응답(`OutingResponse`)과 같은 구조, `status`만 `APPROVED`로
  바뀜(DTO 신규 추가 없이 기존 `OutingResponse` 재사용). **필드명 변경**: `id` → `code`로
  바꾼다(아래 "필드명 변경: id → code" 참고) — 값 자체(외부 식별자 코드)는 그대로이고
  이름만 실제 의미에 맞게 고친다.
```json
{
  "success": true,
  "data": {
    "code": "8A1zx9202",
    "studentNickname": "길동이",
    "studentProfileImageUrl": "https://.../profile/1/abc.jpg?X-Amz-...",
    "studentRealName": "홍길동",
    "studentGrade": 3,
    "studentClassNo": 4,
    "teacherName": "김선생",
    "reason": "치과 진료",
    "outingDate": "20260814",
    "timeSlot": "LUNCH",
    "startTime": "12:30",
    "endTime": "13:40",
    "status": "APPROVED"
  },
  "message": "외출증을 승인했습니다.",
  "code": null
}
```
- **구현 로직**
  1. `SecurityConfig`에 `@EnableMethodSecurity` 활성화(이 이슈의 선행 작업, 아래 "리스크" 참고)
  2. 컨트롤러 메서드에 `@PreAuthorize("hasRole('TEACHER')")` — 역할 자체가 없으면 여기서 `403`
  3. `outingRepository.findByCode(code)`로 `Outing` 조회, 없으면 `404`
  4. `principal.userId() == outing.getTeacher().getId()` 확인(소유권), 아니면 `403`
  5. `outing.getStatus() == PENDING` 확인, 아니면 `409`
  6. `status = APPROVED`, `approvedAt = now`(KST)로 갱신 후 저장
  7. 응답 DTO 변환 — #29의 `OutingService.toResponse(...)`와 동일한 매핑 로직 재사용(신규
     작성 아님)
- **에러**
  - 호출자가 `TEACHER` 역할이 아님 → `403` `OUTING_012`(신규 코드 아님, #29에서 이미 만든
    "STUDENT 아님" 코드와 짝을 맞추는 대신 새 전용 코드가 필요 — 아래 참고)
  - 본인이 지정된 담당 선생님이 아님 → `403` `OUTING_004`
  - 이미 처리된 건(`PENDING`이 아님, `APPROVED`/`REJECTED`/`DEPARTED`/`RETURNED`) → `409`
    `OUTING_005`
  - 존재하지 않는 `code` → `404` `OUTING_006`

> ⚠️ **역할 자체가 없을 때 에러 코드 — 정확한 처리 방법 확정**: `@PreAuthorize("hasRole('TEACHER')")`
> 실패는 Spring Security의 `org.springframework.security.access.AccessDeniedException`으로
> 이어지는데, 이 프로젝트에 아직 이 예외 전용 `GlobalExceptionHandler` 핸들러가 없다(검색
> 결과 없음). 지금 그대로 두면 `handleException` 폴백으로 떨어져 `403`이 아니라 `500`으로
> 응답된다.
>
> **신규 에러 코드는 필요 없다** — `CommonErrorCode.FORBIDDEN`(`COMMON_003`, `403`, "접근
> 권한이 없습니다.")이 이미 정의돼 있는데 지금까지 아무 데서도 안 쓰이고 있었다(전체 검색
> 결과 0건). 정확히 이 상황을 위해 만들어둔 코드로 보고 그대로 재사용한다.
>
> `GlobalExceptionHandler.java`에 기존 핸들러들과 같은 스타일로 추가(위치는
> `handleHttpRequestMethodNotSupported` 뒤, 폴백인 `handleException` 앞 — 클래스 상단 javadoc의
> "처리 우선순위: `CustomException` → Spring MVC 예외 → `Exception`(폴백)" 순서를 그대로
> 따름):
> ```java
> import org.springframework.security.access.AccessDeniedException;
> // ...
> /**
>  * {@link AccessDeniedException} 처리.
>  *
>  * <p>{@code @PreAuthorize} 등 메서드 보안 애노테이션이 접근을 거부했을 때 발생합니다.
>  * 이 프로젝트에서 {@code @EnableMethodSecurity}를 처음 쓰는 #30(외출증 승인)부터
>  * 발생할 수 있습니다.
>  *
>  * @param e 발생한 예외
>  * @return {@code 403 Forbidden} 응답
>  */
> @ExceptionHandler(AccessDeniedException.class)
> public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
>   return ResponseEntity
>       .status(CommonErrorCode.FORBIDDEN.getStatus())
>       .body(ApiResponse.fail(
>           null,
>           CommonErrorCode.FORBIDDEN.getDefaultMessage(),
>           CommonErrorCode.FORBIDDEN.getCode()));
> }
> ```
> 기존 `handleHttpRequestMethodNotSupported`/`handleMissingServletRequestParameter`
> (#32에서 추가)와 완전히 같은 패턴 — 새 예외 타입을 이미 있는 공통 에러 코드로 매핑만 한다.
> 이건 `user`/`outing` 도메인 전용이 아니라 공통 인프라 수정이므로, 이후
> `@PreAuthorize`를 쓰는 모든 엔드포인트(3번 거절 엔드포인트 등)에도 자동으로 적용된다.
>
> ⚠️ **멱등성 트레이드오프(마스터 기획서에서 이미 확정)**: 이미 `APPROVED`인 걸 같은
> 선생님이 실수로 한 번 더 눌러도 `409`를 반환한다(재승인 성공 취급 안 함) — "이미 처리됨"을
> 명확히 알려주는 쪽을 택함. 프론트가 이중 클릭 방지를 안 해도 되게 하려면 멱등 처리(같은
> 상태면 `200` 그대로 반환)로 바꿀 수 있으나, 이번 범위에서는 마스터 기획서의 결정을 그대로
> 따른다.

### 필드명 변경: `OutingResponse.id` → `code` (#30 범위로 포함, 확정)
검토 중 나온 질문("`id` 필드에 왜 `code` 값이 들어가나?")에 대한 답 — 값 자체는 원래부터
내부 PK가 아니라 외부 식별자 코드였다(마스터 기획서 "외부 식별자 정책", #29에서 확정). 문제는
필드 **이름**이 `id`라서 실제로 뭘 담고 있는지 헷갈린다는 점이었고, 이번에 이름을 `code`로
바로잡기로 했다.

> ⚠️ **범위 참고**: `OutingResponse`는 #30만의 DTO가 아니라 #29(신청)에서 이미 만들어 머지된
> 응답 DTO다. 이름을 바꾸면 #30(승인) 응답뿐 아니라 #29의 신청 응답 필드명도 같이 바뀐다 —
> 즉 이 변경은 엄밀히는 #30 자체의 범위가 아니라 #29 산출물에 대한 수정이다. 다만 (1) 아직
> 프론트가 없어 실제 소비자가 없고(`api-design.md` "하위 호환성" 원칙이 명시한 예외 상황과
> 동일 — "아직 프론트가 없는 이 프로젝트 단계에서는 영향이 제한적"), (2) 어차피 #30에서
> `OutingResponse`를 그대로 재사용하므로 지금 고치지 않으면 #30 응답에도 계속 잘못된 이름이
> 남는다는 점을 근거로, 주인 확인 하에 #30에 포함해서 바로 처리한다(범위 분리 대신 즉시 반영
> 선택).

**변경 대상**:
- `OutingResponse` record: `id` 컴포넌트명 → `code`로 변경(타입은 그대로 `String`)
- `OutingService.toResponse(...)`(#29에서 작성): `new OutingResponse(outing.getCode(), ...)`
  호출부는 그대로, 생성자 인자 순서/타입 변경 없음(필드명만 바뀌므로 코드 변경은 record 선언
  한 줄 + 관련 Javadoc)
- 기존 `OutingServiceTest`/`OutingControllerTest`(#29)에서 `response.id()`를 호출하는 부분을
  `response.code()`로 전부 수정
- 문서: `docs/29-outing-apply.md`, `docs/outing-domain.md`의 모든 응답 예시 JSON(`"id":
  "8A1zx9202"` 형태로 등장하는 곳 전부)을 `"code": "8A1zx9202"`로 함께 수정 — 기획서는 항상
  실제 구현과 일치해야 하므로(`branch-workflow.md` 8단계) #29 문서도 같이 고친다.

## 데이터 모델 변경
- 없음. 기존 `Outing` 엔티티의 `status`/`approvedAt` 컬럼(#29에서 이미 만들어둠)만 갱신한다.
  신규 마이그레이션 불필요.
- 신규 에러 코드: `OutingErrorCode`에 `OUTING_004`(403, 담당 선생님 아님)/`OUTING_005`(409,
  이미 처리됨)/`OUTING_006`(404, 존재하지 않는 code) 추가.

## 영향 받는 기존 코드/테스트
- `SecurityConfig`에 `@EnableMethodSecurity` 추가(이 프로젝트 첫 사례) — 위에서 확인했듯
  기존에 잠자고 있던 `@PreAuthorize` 등은 없어서, 이 변경만으로 기존 엔드포인트 동작이
  바뀌지는 않는다.
- `GlobalExceptionHandler`에 `AccessDeniedException` 핸들러 추가(공통 인프라 수정, `user`/
  `outing` 도메인 한정 아님 — #32의 `MissingServletRequestParameterException` 처리 추가와
  같은 성격).
- `OutingController`에 `PATCH /{code}/approve` 추가, `OutingService`에 `approveOuting(...)`
  추가 — `toResponse(...)` 등 #29에서 만든 private 매핑 메서드 재사용.
- `OutingRepository.findByCode`(#29에서 이미 추가된 메서드) 재사용, 신규 쿼리 메서드 없음.
- **`OutingResponse.id` → `code` 필드명 변경(위 절 참고)** — #29 산출물 수정: `OutingResponse`
  record, `OutingServiceTest`/`OutingControllerTest`(#29 작성분)의 `.id()` 호출부,
  `docs/29-outing-apply.md`/`docs/outing-domain.md`의 응답 예시.
- 신규 테스트: `OutingServiceTest`(정상 승인, 담당 선생님 아님 → 403, 이미 처리된 건 → 409,
  없는 code → 404), `OutingControllerTest`(요청 검증), `GlobalExceptionHandlerTest`
  (`AccessDeniedException` → 403 검증), 통합 테스트로 "역할 없는 사용자가 실제로 403을
  받는지"까지 확인(`@EnableMethodSecurity` 누락 시 인가가 조용히 무력화되는 걸 방지 — 이슈
  본문에 이미 명시된 요구사항).

## 리스크 및 고려사항 (api-design.md 6원칙 검토)
- **단일 책임**: 승인 하나만 다룬다. 거절은 완전히 같은 골격(3번 엔드포인트)이지만 사유
  저장이라는 차이가 있어 이슈를 분리해 후속으로 진행한다.
- **일관성**: 응답 DTO를 신규로 만들지 않고 #29의 `OutingResponse`를 그대로 재사용해 "같은
  자원은 같은 모양으로 응답한다"는 일관성을 지킨다.
- **의미 있는 오류**: 위 `AccessDeniedException` 공백을 이 이슈에서 반드시 메운다 — 안 그러면
  "권한 없음"이 `500`으로 응답되어 클라이언트가 원인을 알 수 없다.
- **확장성/성능**: 단건 조회/갱신이라 페이지네이션 등 해당 없음.
- **하위 호환성**: `@EnableMethodSecurity` 활성화가 유일한 전역 변경인데, 위에서 검증했듯
  기존에 의존하던 코드가 없어 안전. 신규 에러 코드 추가는 기존 클라이언트에 영향 없음.
- `@EnableMethodSecurity`를 `SecurityConfig`에 바로 추가할지, 별도 `@Configuration` 클래스로
  분리할지는 구현 시 결정(이슈 본문에 이미 "SecurityConfig 또는 별도 Configuration"으로
  열려있음) — 기존 파일에 애노테이션 하나만 추가하는 쪽이 더 단순해 보이지만, 리뷰 시 확정.
