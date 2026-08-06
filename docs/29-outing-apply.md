# #29 외출증 신청 API

관련 이슈: https://github.com/GBSW-ReMake/GONE-server-V1/issues/29
전체 도메인 마스터 기획서: [outing-domain.md](./outing-domain.md) (승인/거절/출발·도착/위치
추적/가시성/복귀 리마인더 등 전체 흐름은 여기 있고, 이 문서는 그중 "신청" 하나만 좁힌 것)

## 개요/목적
학생이 정해진 시간대(점심/저녁 프리셋, 또는 직접 입력한 커스텀 시간대)에 외출증을 신청하는
API 하나만 구현한다. 승인/거절, 출발/도착, 위치 추적 등은 각각 후속 이슈(#30, #31, ...)로
따로 진행한다.

## 엔드포인트

### `POST /api/v1/outings`
- **인증/권한**: `STUDENT`
- **요청** (프리셋)
```json
{
  "reason": "치과 진료",
  "outingDate": "20260814",
  "timeSlot": "LUNCH",
  "teacherUserId": 42
}
```
- **요청** (커스텀 — `timeSlot: "CUSTOM"`일 때만 `customStartTime`/`customEndTime` 필요)
```json
{
  "reason": "치과 진료",
  "outingDate": "20260814",
  "timeSlot": "CUSTOM",
  "customStartTime": "14:00",
  "customEndTime": "16:00",
  "teacherUserId": 42
}
```
- **응답** (`201 Created`)
```json
{
  "success": true,
  "data": {
    "id": "8A1zx9202",
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
  1. `@AuthenticationPrincipal`에서 `studentUserId` 추출 후
     `UserRoleRepository.findRoleCodesByUserId(studentUserId)`로 `STUDENT` 역할 보유 확인 →
     없으면 거부
  2. Bean Validation: `reason` not blank, `outingDate` 파싱 가능, `timeSlot` enum 값 유효.
     `timeSlot == CUSTOM`이면 `customStartTime`/`customEndTime` 필수(그 외 값이면 두 필드는
     무시)
  3. 날짜 범위 검증: `outingDate < 오늘(KST)`이거나 이번 주 금요일을 넘으면 거부
     (`LocalDate.now(KST).with(DayOfWeek.FRIDAY)`)
  4. 시작/종료 시각 확정:
     - `LUNCH`/`DINNER`: 서버가 프리셋 값으로 채움
     - `CUSTOM`: `customStartTime >= 08:40`, `customEndTime <= 20:30`,
       `customEndTime > customStartTime` 검증 → 벗어나면 거부
  5. 마감 시각 검증: `outingDate == 오늘`이면서 `현재시각 >= (확정된) startTime`이면 거부
  6. `UserRoleRepository.findRoleCodesByUserId(teacherUserId)`로 `TEACHER` 포함 여부 확인 →
     없으면 거부
  7. `userRepository.findByIdForUpdate(studentUserId)`로 그 학생의 `User` 행에 배타적 락
     (`SELECT ... FOR UPDATE`) — 이 지점부터 저장까지 같은 학생의 동시 요청은 직렬화된다
     (아래 "동시성 처리" 참고)
  8. 학급 배정 확인: `student.getGbsw()`의 `grade`/`classNo`가 하나라도 `null`이면 거부
     (`GbswErrorCode.NO_CLASS_ASSIGNED` 재사용 — `TimetableService.getMyTimetable`과 동일한
     방어. 외출증은 학년/반을 반드시 담는 공적 문서라, 학급 미배정 상태로 발급되는 걸 막는다)
  9. 겹침 확인: 락을 잡은 채로 `(studentUserId, outingDate)`의 활성(`PENDING`/`APPROVED`/
     `DEPARTED`) 외출증을 조회해, 확정된 `[startTime, endTime)` 구간이 하나라도 겹치면 거부
     (`OutingTimeUtils.overlaps(...)` 순수 함수로 분리)
  10. `OutingCodeGenerator`로 `code` 생성(영숫자 10자리, 예: `8A1zx9202`) 후 `Outing` 저장
      (`PENDING`) — `code` 유니크 제약 위반 시(확률상 희박) 재생성 후 재시도(최대 5회). 5회를
      모두 소진하면 원본 `DataIntegrityViolationException`을 그대로 던진다(원인 불문 삼키지
      않음) — `GlobalExceptionHandler`의 공통 핸들러가 `409`로 변환한다.
  11. 응답 DTO 변환 — **외출증은 공적 문서라 서비스 닉네임/사진과 별개로 실명/학년/반도
      같이 담는다**(마스터 기획서 "정책 가정" 참고):
      - `id = outing.getCode()`(내부 PK가 아니라 프론트에 표시할 코드를 응답 `id`로 사용 —
        마스터 기획서 "외부 식별자 정책" 참고)
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
  - `outingDate`가 오늘인데 이미 확정된 `startTime`이 지남 → `400` `OUTING_001`
  - `CUSTOM`인데 `customStartTime`/`customEndTime`이 08:40~20:30 범위 밖이거나 `end <= start`
    → `400` `OUTING_011`
  - `teacherUserId`가 `TEACHER` 역할이 아님 → `400` `OUTING_002`
  - 호출 학생 계정이 학급 미배정 상태(`Gbsw.grade`/`classNo`가 `null`) → `400` `GBSW_002`
    (`GbswErrorCode` 재사용, 신규 `OUTING_` 코드 아님)
  - 그날 다른 활성 외출증과 시간이 겹침 → `409` `OUTING_003`
  - 호출자가 `STUDENT` 역할이 아님 → `403` `OUTING_012`

> ⚠️ 동시성 처리 (확정, 마스터 기획서 "동시성 처리" 참고): 겹침 확인(8번)과 저장(9번) 사이
> TOCTOU 레이스는 두 겹으로 막는다.
> - **프론트엔드**: 신청 버튼 클릭 즉시 비활성화(더블클릭 방지)
> - **서버(최종 방어선)**: "겹침 여부"는 정확히 같은 값인지가 아니라 구간이 겹치는지를
>   계산해야 해서 DB 유니크 인덱스로는 표현할 수 없다(MySQL엔 PostgreSQL의 EXCLUDE 제약
>   같은 게 없음). 대신 그 학생의 `User` 행에 `SELECT ... FOR UPDATE`(`@Lock
>   (LockModeType.PESSIMISTIC_WRITE)`)로 배타적 락을 걸어 같은 학생의 신청 처리를 완전히
>   직렬화한다 — 다른 학생끼리는 서로 다른 행을 잠그므로 전혀 안 기다린다. 이 프로젝트에서
>   비관적 락을 쓰는 첫 사례.

## 데이터 모델 변경
- 신규 마이그레이션 1개, `outing` 테이블 생성:
  - `id`(내부 PK), `code`(외부 식별자, `VARCHAR(10)` UNIQUE, 응답의 `id`로 노출 — 마스터
    기획서 "외부 식별자 정책" 참고), `student_user_id`(FK), `teacher_user_id`(FK), `reason`,
    `outing_date`, `time_slot`, `start_time`, `end_time`, `status`, `approved_at`,
    `rejected_reason`, `departed_at`, `returned_at`, `created_at`
  - 이 이슈에서는 `status`가 `PENDING`만 실제로 쓰이지만, 컬럼 자체는 전체 흐름(#30/#31
    이후 출발/도착까지)을 고려해 처음부터 다 만들어둔다 — 나중에 컬럼 추가 마이그레이션을
    반복하지 않기 위함.
  - 인덱스: `(student_user_id, outing_date)` — 겹침 확인 조회에 사용(정확한 시간 겹침 판단은
    애플리케이션 코드에서, 위 "동시성 처리" 참고)
- 신규 엔티티: `Outing`, `OutingTimeSlot`(enum: `LUNCH`/`DINNER`/`CUSTOM`), `OutingStatus`(enum)
- 신규 유틸: `OutingCodeGenerator`(`SecureRandom` 기반 10자리 영숫자 코드 생성),
  `OutingTimeUtils`(구간 겹침 판단 순수 함수)
- `UserRepository`에 `findByIdForUpdate(Long id)`(`@Lock(PESSIMISTIC_WRITE)`) 메서드 추가
- 신규 에러 코드: `OutingErrorCode`(`OUTING_001`~`OUTING_003`, `OUTING_011`, `OUTING_012`) —
  `outing/exception` 패키지

## 영향 받는 기존 코드/테스트
- `UserRepository`에 락 조회 메서드 1개 추가(기존 로직 변경 없음), `SecurityConfig`의
  `authorizeHttpRequests`에 `/api/v1/outings/**` → `authenticated()` 한 줄 추가(`timetables`/
  `users`/`files`와 같은 패턴) — 그 외 기존 코드 수정 없음(신규 `outing` 패키지만 추가) —
  `controller`/`service`/`dto`/`exception`/`entity`/`repository` 서브패키지 구조로 기존
  컨벤션 그대로.
- `UserRoleRepository.findRoleCodesByUserId`(기존 메서드) 재사용해 `teacherUserId` 검증 및
  호출자의 `STUDENT` 역할 확인(둘 다 같은 패턴).
- `R2FileService.generateDownloadUrl`(기존 메서드) 재사용해 학생 프로필 사진 URL 생성 —
  `OutingService`가 `R2FileService`에 의존하게 된다.
- `GbswErrorCode.NO_CLASS_ASSIGNED`(`gbsw` 도메인 기존 에러 코드) 재사용해 학급 미배정 학생
  계정을 거부 — `TimetableService.getMyTimetable`이 이미 같은 필드(`Gbsw.grade`/`classNo`)에
  쓰던 방어를 그대로 가져옴, 신규 `OUTING_` 코드 추가 아님.
- `OutingController`가 `LocalDate.now(KST)`/`LocalTime.now(KST)`를 각각 따로 호출하던 걸
  `LocalDateTime.now(KST)` 한 번으로 스냅샷 떠서 날짜/시각을 나누는 방식으로 바꿈 — 두 번
  따로 호출하면 자정 경계에서 날짜와 시각이 서로 다른 순간 값으로 섞일 수 있어서다.
- `OutingService.saveWithGeneratedCode`가 `code` 재시도를 모두 소진했을 때 더 이상
  `CustomException(INTERNAL_SERVER_ERROR)`로 감싸지 않고, 원본 `DataIntegrityViolationException`
  을 그대로 던진다 — `code` 충돌이 아닌 다른 제약 위반이어도 원인을 잃지 않고
  `GlobalExceptionHandler`의 공통 핸들러가 `409`로 변환하도록 위임한다.
- 신규 테스트: `OutingServiceTest`(정상 신청 — 프리셋/커스텀 각각, 에러 케이스 전부 — 학급
  미배정 포함, `code` 중복 시 재생성 후 저장 성공, 재시도 소진 시 원본 예외 전파, 겹침 판정
  경계값), `OutingTimeUtilsTest`(구간 겹침 단위 테스트), `OutingControllerTest`(요청 검증)

## 리스크 및 고려사항
- 담당 선생님을 학생이 매번 직접 지정해야 하는 구조(담임 개념이 아직 없음) — 마스터
  기획서에서 이미 확정된 정책 가정.
- `OutingErrorCode`는 이후 이슈(#30 승인, #31 거절)에서 `OUTING_004`부터 이어서 추가된다 —
  이 이슈에서는 `001`/`002`/`003`/`011`만 채운다.
- `status`가 `PENDING` 외 다른 값으로 바뀌는 흐름(승인/거절/출발/도착)은 이 이슈 범위 밖이라
  아직 아무 API도 그 값들을 만들지 않는다 — DB에 `PENDING` 상태 레코드만 쌓이는 게 정상.
- 비관적 락(`PESSIMISTIC_WRITE`)이 이 프로젝트 첫 사례라, 통합 테스트로 "동시 요청 시 하나만
  성공하고 나머지는 겹침 에러를 받는지" 실제로 검증해야 한다(단위 테스트만으론 락 동작 자체를
  검증하기 어려움).
