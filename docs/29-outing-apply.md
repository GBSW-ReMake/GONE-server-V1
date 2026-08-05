# #29 외출증 신청 API

관련 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/29
전체 도메인 마스터 기획서: [outing-domain.md](./outing-domain.md) (승인/거절/출발·도착/위치
추적/가시성/복귀 리마인더 등 전체 흐름은 여기 있고, 이 문서는 그중 "신청" 하나만 좁힌 것)

## 개요/목적
학생이 정해진 시간대(점심/저녁)에 외출증을 신청하는 API 하나만 구현한다. 승인/거절, 출발/
도착, 위치 추적 등은 각각 후속 이슈(#30, #31, ...)로 따로 진행한다.

## 엔드포인트

### `POST /api/v1/outings`
- **인증/권한**: `STUDENT`
- **요청**
```json
{
  "reason": "치과 진료",
  "outingDate": "20260814",
  "timeSlot": "LUNCH",
  "teacherUserId": 42
}
```
- **응답** (`201 Created`)
```json
{
  "success": true,
  "data": {
    "id": 501,
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
    "status": "PENDING"
  },
  "message": "외출증 신청이 접수되었습니다.",
  "code": null
}
```
- **구현 로직**
  1. `@AuthenticationPrincipal`에서 `studentUserId` 추출
  2. Bean Validation: `reason` not blank, `outingDate` 파싱 가능, `timeSlot` enum 값 유효
  3. 날짜 범위 검증: `outingDate < 오늘(KST)`이거나 이번 주 금요일을 넘으면 거부
     (`LocalDate.now(KST).with(DayOfWeek.FRIDAY)`)
  4. 마감 시각 검증: `outingDate == 오늘`이면서 `현재시각 >= timeSlot.startTime`이면 거부
  5. `UserRoleRepository.findRoleCodesByUserId(teacherUserId)`로 `TEACHER` 포함 여부 확인 →
     없으면 거부
  6. 중복 확인: `(studentUserId, outingDate, timeSlot)`로 `PENDING`/`APPROVED`/`DEPARTED`
     상태 레코드 존재 여부 조회 → 있으면 거부
  7. `Outing` 저장(`PENDING`, `start_time`/`end_time`은 `timeSlot`에서 채움)
  8. 응답 DTO 변환 — **외출증은 공적 문서라 서비스 닉네임/사진과 별개로 실명/학년/반도
     같이 담는다**(마스터 기획서 "정책 가정" 참고):
     - `studentNickname = student.getName()`,
       `studentProfileImageUrl = student.getProfileImageKey() != null ?
       r2FileService.generateDownloadUrl(key) : null` (`R2FileService.generateDownloadUrl`은
       기존 메서드 재사용, 신규 아님)
     - `studentRealName = student.getGbsw().getName()`,
       `studentGrade = student.getGbsw().getGrade()`,
       `studentClassNo = student.getGbsw().getClassNo()`
     - `teacherName = teacher.getGbsw().getName()`(선생님은 실명만, 닉네임/사진 없음)
- **에러**
  - `outingDate`가 과거이거나 이번 주 범위를 벗어남 → `400` `OUTING_001`
  - `outingDate`가 오늘인데 이미 그 `timeSlot` 시작 시각이 지남 → `400` `OUTING_001`
  - `teacherUserId`가 `TEACHER` 역할이 아님 → `400` `OUTING_002`
  - 같은 날짜+시간대로 이미 진행 중인 외출증이 있음 → `409` `OUTING_003`

> ⚠️ 동시성 주의(마스터 기획서에서 이미 짚음): 6번(중복 체크)과 7번(저장) 사이 TOCTOU
> 레이스가 있을 수 있다. `(student_user_id, outing_date, time_slot)`에 DB 유니크 제약을
> 마지막 방어선으로 걸되, `REJECTED` 건 재신청은 허용해야 하므로 "진행 중인 상태에서만"
> 유니크하게 거는 방법(부분 인덱스 등)을 구현 시 정한다. 완전 동시 이중 클릭은 실사용에서
> 드문 케이스라 애플리케이션 레벨 체크만으로 충분하다고 볼 수도 있다 — 구현 시 최종 결정.

## 데이터 모델 변경
- 신규 마이그레이션 1개, `outing` 테이블 생성:
  - `id`, `student_user_id`(FK), `teacher_user_id`(FK), `reason`, `outing_date`, `time_slot`,
    `start_time`, `end_time`, `status`, `approved_at`, `rejected_reason`, `departed_at`,
    `returned_at`, `created_at`
  - 이 이슈에서는 `status`가 `PENDING`만 실제로 쓰이지만, 컬럼 자체는 전체 흐름(#30/#31
    이후 출발/도착까지)을 고려해 처음부터 다 만들어둔다 — 나중에 컬럼 추가 마이그레이션을
    반복하지 않기 위함.
  - 인덱스: `(student_user_id, outing_date, time_slot)`
- 신규 엔티티: `Outing`, `OutingTimeSlot`(enum), `OutingStatus`(enum)
- 신규 에러 코드: `OutingErrorCode`(`OUTING_001`~`OUTING_003`) — `outing/exception` 패키지

## 영향 받는 기존 코드/테스트
- 기존 코드 수정 없음(신규 `outing` 패키지만 추가) — `controller`/`service`/`dto`/`exception`/
  `entity`/`repository` 서브패키지 구조로 기존 컨벤션 그대로.
- `UserRoleRepository.findRoleCodesByUserId`(기존 메서드) 재사용해 `teacherUserId` 검증.
- `R2FileService.generateDownloadUrl`(기존 메서드) 재사용해 학생 프로필 사진 URL 생성 —
  `OutingService`가 `R2FileService`에 의존하게 된다.
- 신규 테스트: `OutingServiceTest`(정상 신청, 4가지 에러 케이스), `OutingControllerTest`(요청
  검증)

## 리스크 및 고려사항
- 담당 선생님을 학생이 매번 직접 지정해야 하는 구조(담임 개념이 아직 없음) — 마스터
  기획서에서 이미 확정된 정책 가정.
- `OutingErrorCode`는 이후 이슈(#30 승인, #31 거절)에서 `OUTING_004`부터 이어서 추가된다 —
  이 이슈에서는 `001`~`003`까지만 채운다.
- `status`가 `PENDING` 외 다른 값으로 바뀌는 흐름(승인/거절/출발/도착)은 이 이슈 범위 밖이라
  아직 아무 API도 그 값들을 만들지 않는다 — DB에 `PENDING` 상태 레코드만 쌓이는 게 정상.
