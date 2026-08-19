# #68 스쿨캠핑 신청(선착순, 팀 단위) API — 기획서

관련 이슈: [#68 스쿨캠핑 신청(선착순, 팀 단위) API 구현](https://github.com/GBSW-ReMake/GONE-server-V1/issues/68)
마스터 기획서: [1_schoolcamp-domain.md](./1_schoolcamp-domain.md)의 "도메인 모델 —
`SchoolCampApplication`/`SchoolCampMember`", "엔드포인트 2.
`POST /api/v1/school-camps/{sessionId}/applications`"
선행 이슈: [#67](./67-schoolcamp-session-calendar.md)(완료·머지) — 이 이슈가 의존하는
`SchoolCampSession`을 만들었다.

> **갱신 (2026-08-19)**: 재학생 300명 규모에서 한 세션(날짜)에 100명 이상이 동시에 신청을
> 시도할 수 있다는 전제가 확인되어, "구현 로직"의 점유(claim) 방식을 재검토했다. 기존안은
> claim을 신청 저장/알림 발송과 같은 트랜잭션에 두고 검증 실패 시 자동 롤백으로 반환하는
> 방식이었는데, 이 프로젝트 HikariCP 풀이 기본값(10)이라 그 방식으로는 순간 경합이 커넥션
> 풀 전체를 묶어 무관한 다른 API까지 지연시킬 수 있다는 문제가 드러났다. **claim을 별도의
> 짧은 트랜잭션으로 즉시 커밋하고, 실패 시 명시적으로 반환하는 방식으로 확정했다** — 아래
> "구현 로직" 3번과 "리스크 및 고려사항"의 "커넥션 풀 경합" 절 참고.

## 개요/목적
대표 학생 1명이 팀(본인 포함 최대 8명)을 한 번에 등록해 스쿨캠핑에 신청하는
`POST /api/v1/school-camps/{sessionId}/applications`를 구현한다. 한 세션(날짜)에 성사되는
신청은 정확히 1건뿐이라, "그 날짜를 딱 한 팀만 차지하게 지키는" 동시성 처리가 이 이슈의
핵심이다.

**이슈 본문 대비 범위 보정 2건(검토 요청)**: 이슈 본문의 "작업 범위" 체크리스트에는 없지만,
마스터 기획서와 #67 기획서가 이미 "#68 담당"으로 명시해둔 항목이 있어 이번 범위에 포함했다.
1. **`SCHOOLCAMP_001`(404, 세션 없음) 추가** — 이슈 본문은 `002`/`003`/`004`/`008`만
   나열했지만, 마스터 기획서 엔드포인트 2의 에러 표에 "존재하지 않는 세션 → `404`
   `SCHOOLCAMP_001`"이 있고 실제 구현 1단계("`id`로 세션 조회, 없으면 `404`")에 바로
   필요하다 — 목록에서 빠진 것으로 보고 포함한다.
2. **`GbswUtils.studentNumber(Gbsw)` 유틸 신설 + `GET /api/v1/school-camps` 캘린더 응답의
   `teacherDisplayName`/`applicantDisplayName` 채우기** — #67 기획서가 "이 두 필드를 실제
   신청 정보에서 채우는 로직은 #68이 담당(그때 이 메서드에 조인 쿼리가 추가된다)"라고 명시적으로
   위임해뒀다. `SchoolCampApplication`이 이 이슈에서 처음 생기므로, 이 이슈가 끝나야 그 조인이
   가능해진다 — 이슈 본문 체크리스트 누락으로 보고 포함한다.

## 도메인 모델 (신규)
마이그레이션 `V12__add_schoolcamp_application.sql`:
```sql
CREATE TABLE school_camp_application (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    applicant_user_id BIGINT NOT NULL,
    teacher_user_id BIGINT NULL,
    teacher_name VARCHAR(50) NULL,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at DATETIME NULL,

    FOREIGN KEY (session_id) REFERENCES school_camp_session(id),
    FOREIGN KEY (applicant_user_id) REFERENCES user(id),
    FOREIGN KEY (teacher_user_id) REFERENCES user(id),
    KEY idx_application_session_applicant (session_id, applicant_user_id),
    KEY idx_application_applicant_applied (applicant_user_id, applied_at)
);

CREATE TABLE school_camp_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    application_id BIGINT NOT NULL,
    student_user_id BIGINT NULL,
    guest_name VARCHAR(50) NULL,
    is_applicant BOOLEAN NOT NULL,

    FOREIGN KEY (application_id) REFERENCES school_camp_application(id),
    FOREIGN KEY (student_user_id) REFERENCES user(id),
    KEY idx_member_application (application_id),
    KEY idx_member_student (student_user_id)
);
```
- `SchoolCampApplication` = 신청 1건(팀 1개). `teacher_user_id`/`teacher_name` 중 정확히
  하나만 값을 가진다(서비스 레벨 검증, 이 프로젝트에 CHECK 제약 전례 없음 — 마스터 기획서
  그대로). `cancelled_at`은 이 이슈에서 채우는 코드가 없다(취소는 #70 몫) — 컬럼만 미리
  만들어둔다.
- `SchoolCampMember` = 팀원 1명(대표 신청자 본인 포함, `is_applicant=true`인 행 1개 +
  나머지). `student_user_id`/`guest_name` 중 정확히 하나만 값을 가진다.
- 인덱스 근거는 마스터 기획서 "도메인 모델" 절 그대로(본인 신청 내역 조회는 #69 몫이지만
  인덱스는 스키마 변경 비용이 크므로 이 이슈에서 미리 만들어둔다).

```java
package com.remake.gone.schoolcamp.entity;

@Entity
@Table(name = "school_camp_application")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SchoolCampApplication {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "session_id", nullable = false)
  private SchoolCampSession session;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "applicant_user_id", nullable = false)
  private User applicant;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "teacher_user_id")
  private User teacherUser;

  @Column(name = "teacher_name", length = 50)
  private String teacherName;

  @CreationTimestamp
  @Column(name = "applied_at", nullable = false, updatable = false)
  private LocalDateTime appliedAt;

  @Column(name = "cancelled_at")
  private LocalDateTime cancelledAt;
}
```
```java
package com.remake.gone.schoolcamp.entity;

@Entity
@Table(name = "school_camp_member")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SchoolCampMember {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "application_id", nullable = false)
  private SchoolCampApplication application;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "student_user_id")
  private User studentUser;

  @Column(name = "guest_name", length = 50)
  private String guestName;

  @Column(name = "is_applicant", nullable = false)
  private boolean applicant;
}
```

## `GbswUtils` (신규, `gbsw.utils` 패키지)
```java
package com.remake.gone.gbsw.utils;

public final class GbswUtils {

  private GbswUtils() {}

  /**
   * 학생의 학번(학년+반+번호)을 고정 4자리 문자열로 반환한다. 반이 10개 미만이라는 전제로
   * 학년 1자리 + 반 1자리 + 번호 2자리(0채움)를 그대로 이어붙인다 — 반이 10개 이상으로
   * 늘어나면 이 포맷을 바꿔야 한다({@code AuthService.generateStudentDefaultName}과 근거 공유).
   *
   * @param gbsw 학번을 계산할 학생 학적(반드시 {@code GbswType.STUDENT})
   * @return 고정 4자리 학번 문자열(예: 3학년 2반 18번 → {@code "3218"})
   */
  public static String studentNumber(Gbsw gbsw) {
    return "%d%d%02d".formatted(gbsw.getGrade(), gbsw.getClassNo(), gbsw.getNumber());
  }
}
```
- `AuthService.generateStudentDefaultName`을 `GbswUtils.studentNumber(gbsw) + gbsw.getName()`
  형태로 리팩터링한다(동작 변화 없음, null 체크·예외 메시지는 `AuthService` 쪽에 그대로 남긴다
  — `GbswUtils`는 순수 포맷 계산만 담당). 반이 10개 미만이라는 전제가 깨졌을 때 고칠 곳이
  한 곳(`GbswUtils`)으로 줄어든다.

## 엔드포인트

### `POST /api/v1/school-camps/{sessionId}/applications` — 신청(선착순, 팀 단위)
**권한**: `STUDENT`(`@PreAuthorize("hasRole('STUDENT')")`)

**요청** — 대표 신청자 본인은 목록에 안 적는다(자동 포함), `additionalMembers`는 0~7개
```json
{
  "teacherUserId": 42,
  "teacherName": null,
  "additionalMembers": [
    { "studentUserId": 55, "guestName": null },
    { "studentUserId": null, "guestName": "김철수(옆반 아님, 외부인)" }
  ]
}
```

**응답** (`201 Created`)
```json
{
  "success": true,
  "data": {
    "id": 301,
    "campDate": "20260403",
    "teacherDisplayName": "박선생",
    "members": [
      { "studentRealName": "홍길동", "studentGrade": 3, "studentClassNo": 4, "guestName": null, "isApplicant": true },
      { "studentRealName": "이영희", "studentGrade": 3, "studentClassNo": 2, "guestName": null, "isApplicant": false },
      { "studentRealName": null, "studentGrade": null, "studentClassNo": null, "guestName": "김철수(옆반 아님, 외부인)", "isApplicant": false }
    ],
    "appliedAt": "2026-03-20T09:12:00"
  },
  "message": "스쿨캠핑 신청이 완료되었습니다.",
  "code": null
}
```

**구현 로직 — 순서가 핵심이다(트래픽 집중 대응, 확정)**: 인기 날짜는 등록이 열리는
순간 수백 건이 같은 세션 1행을 놓고 동시에 경쟁한다. "검증을 다 하고 마지막에 점유"하면,
결국 탈락할 수백 건이 탈락하기 전에 담당 선생님 조회/팀원 존재 확인/월 중복 확인까지 DB를
여러 번 두드리게 되어 트래픽이 몰리는 그 순간에 부하가 최대가 된다. 그래서 **DB 조회가
필요 없는 형식 검증만 먼저 하고, 곧바로 점유를 시도해 대부분의 탈락자를 그 자리에서
걸러낸 뒤, DB 조회가 필요한 무거운 검증은 "그 순간의 당첨자" 한 명에게만** 수행한다.

**점유(claim)는 신청 저장/알림 발송과 분리된 별도 트랜잭션으로 즉시 커밋한다(확정,
2026-08-19)** — 상세 근거는 아래 "리스크 및 고려사항"의 "커넥션 풀 경합" 절 참고. 요약:
재학생 300명 규모에서 한 세션에 100명 이상 동시 신청이 몰릴 수 있는데, 이 프로젝트
HikariCP 풀은 기본값(10)이다. 당첨자가 4~8번(DB 왕복 12~20회 안팎) 내내 이 세션 행의
배타 락을 쥐고 있으면, 그 순간 몰린 나머지 요청들이 커넥션 자체를 못 얻어 스쿨캠핑과
무관한 다른 API까지 서버 전체가 같이 지연될 수 있다. claim을 별도 트랜잭션으로 즉시
커밋하면 락 보유 시간이 "UPDATE 한 문장" 수준(수 ms)으로 줄어 이 문제가 사라진다.

1. `sessionId`로 `SchoolCampSession` 조회, 없으면 `404` `SCHOOLCAMP_001`(PK 단건 조회라
   저렴 — 트래픽 집중과 무관하게 항상 필요)
2. **DB 조회 없는 형식 검증만** 먼저 수행(전부 요청 바디만으로 판단 가능):
   `teacherUserId`/`teacherName` 중 정확히 하나, `additionalMembers`의 각 항목도
   `studentUserId`/`guestName` 중 정확히 하나씩 → 위반 시 `400` `SCHOOLCAMP_004`.
   `memberCount = 1(본인) + additionalMembers.size()` 계산, `memberCount > 8`이면 `400`
   `SCHOOLCAMP_004`
3. **세션 원자적 점유(claim)를 별도 트랜잭션으로 즉시 시도·커밋한다** — 아직 담당
   선생님/팀원 존재/월 중복을 확인하지 않은 채로 먼저 자리를 선점한다. 신규 컴포넌트
   `SchoolCampSessionClaimService`(`service` 패키지)를 만들어 `claim`/`release` 두
   메서드를 `@Transactional(propagation = Propagation.REQUIRES_NEW)`로 선언한다 —
   `SchoolCampService.applyToCamp` 자신의 트랜잭션 안에서 `this.claim(...)`처럼 자기
   자신을 호출하면 AOP 프록시를 안 거쳐 `REQUIRES_NEW`가 적용되지 않으므로, 반드시 별도
   빈으로 분리해 주입받아 호출해야 한다.

   `SchoolCampSessionRepository`에 `@Modifying` 조건부 `UPDATE` 두 개를 추가한다(점유용
   `claim`, 반환용 `release` — `release`는 4번 엔드포인트의 취소 로직이 이미 쓰는 반환
   방향과 같은 패턴):
   ```java
   @Modifying
   @Query("update SchoolCampSession s set s.takenAt = :takenAt "
       + "where s.id = :id and s.takenAt is null")
   int claim(@Param("id") Long id, @Param("takenAt") LocalDateTime takenAt);

   @Modifying
   @Query("update SchoolCampSession s set s.takenAt = null where s.id = :id")
   int release(@Param("id") Long id);
   ```
   `SchoolCampSessionClaimService`는 이 두 메서드를 각각 감싸기만 한다:
   ```java
   @Service
   @RequiredArgsConstructor
   public class SchoolCampSessionClaimService {

     private final SchoolCampSessionRepository sessionRepository;

     @Transactional(propagation = Propagation.REQUIRES_NEW)
     public boolean claim(Long sessionId, LocalDateTime takenAt) {
       return sessionRepository.claim(sessionId, takenAt) == 1;
     }

     @Transactional(propagation = Propagation.REQUIRES_NEW)
     public void release(Long sessionId) {
       sessionRepository.release(sessionId);
     }
   }
   ```
   `claim`이 `false`를 반환하면(이미 다른 신청이 이 날짜를 선점) **즉시** `409`
   `SCHOOLCAMP_002`로 거부하고 끝낸다 — 탈락하는 대다수 요청은 이 지점에서 짧은 트랜잭션
   하나만 실행하고 끝나며, 그 뒤의 4~6번(선생님 역할 확인, 팀원 조회, 월 중복 조회)은
   실행되지 않는다. `true`면 다음 단계로 — 이 요청이 "그 순간의 당첨자"이고, 이 시점부터
   이 세션은 이미 커밋되어 점유된 상태다(4~8번이 실패해도 이 커밋 자체는 되돌아가지 않는다
   — 아래 "실패 시 반환" 참고).
4. *(당첨자만 도달)* `teacherUserId`가 주어졌다면 `TEACHER` 역할 확인(`UserRoleRepository
   .findRoleCodesByUserId`, `outing`의 `findValidTeacher`와 동일 패턴), 아니면 `400`
   `SCHOOLCAMP_004`
5. *(당첨자만 도달)* `additionalMembers`의 `studentUserId`들이 실제 존재하는 사용자인지
   확인 — 개별 조회 대신 `userRepository.findAllById(candidateIds)` **배치 조회 1회**로
   확인(존재하지 않으면 `400` `SCHOOLCAMP_008`). **중복 검사는 이 존재 확인과 분리해
   명시적으로 한다** — `candidateIds`를 dedupe하지 않은 채 그대로 `findAllById`에 넘기면
   `IN` 절 특성상 중복 id가 한 번만 매칭돼 반환 개수가 우연히 줄어들긴 하지만, 그건
   암묵적인 부작용이라 의도가 드러나지 않는다. `registerCampDates`(날짜 중복 검증,
   `SchoolCampService.java:61`)와 동일하게 `new HashSet<>(candidateIds).size() !=
   candidateIds.size()`로 명시적으로 검사한다. 대표 신청자 본인이 `candidateIds`에
   포함됐는지도 별도로 확인한다(본인은 실제 존재하는 유저라 존재 확인만으로는 절대 안
   걸러진다) — 셋 중 하나라도 위반 시 `400` `SCHOOLCAMP_008`
6. *(당첨자만 도달)* **월 중복 참여 확인**(대표/팀원 구분 없이): 대표 신청자 +
   `additionalMembers`의 `studentUserId` 전체를 후보 집합으로 모아, `SchoolCampMember`를
   `SchoolCampApplication`/`SchoolCampSession`과 조인해 이 세션이 속한 달
   (`session.campDate`의 `YearMonth`) 안에서 `cancelled_at IS NULL`인 신청에 속한
   `student_user_id` 집합과 겹치는지 확인 → 하나라도 겹치면 `409` `SCHOOLCAMP_003`
7. `SchoolCampApplication` 저장 + `SchoolCampMember` `memberCount`명 저장(대표 신청자 1행
   포함, `applicant=true`)
8. 가입된 학생으로 추가된 팀원(`applicant=false` && `studentUser`가 있는 행)마다
   `notificationService.send(studentUserId, "스쿨캠핑에 초대되었어요",
   "OO님이 MM월 DD일 스쿨캠핑에 초대했어요.", NotificationType.SCHOOLCAMP)` 호출
9. 응답 DTO 변환 — 가입된 팀원은 `Gbsw`의 실명/학년/반 표시, "기타"는 `guestName` 그대로

> ⚠️ **4~8번 중 하나라도 실패하면 3번의 claim은 더 이상 자동으로 롤백되지 않는다** — claim이
> 별도 트랜잭션으로 이미 커밋됐기 때문에, `SchoolCampService.applyToCamp`의 트랜잭션이
> 롤백돼도 `taken_at`은 그대로 남는다. 그래서 `applyToCamp`는 4~8번을 `try`로 감싸고,
> `catch` 블록에서 `schoolCampSessionClaimService.release(sessionId)`를 **명시적으로
> 호출한 뒤** 원래 예외를 다시 던진다 — `release`도 `REQUIRES_NEW`라 바깥 트랜잭션의
> 롤백 여부와 무관하게 독립적으로 커밋된다. 당첨자가 뒤늦게 자격 미달로 걸러지면 이 명시적
> 반환으로 세션이 다시 열린다.
>
> **잔여 리스크(수용, 2026-08-19)**: `release` 호출 자체가 실패하면(DB 순단 등) 세션은
> 실제로 비어있는데 `taken_at`만 채워진 채 남는 "유령 점유" 상태가 될 수 있다. 이 이슈
> 범위에서는 이 실패에 대한 재시도·보정 로직을 추가하지 않는다 — 발생 조건이 이미 극히
> 드문 인프라 장애 상황이라, 이를 막기 위한 "보상의 보상" 로직을 넣는 비용이 실익보다
> 크다고 판단한다. 발생하면 관리자가 수동으로 `taken_at`을 비우는 것으로 충분하다.

**에러**
- 존재하지 않는 세션 → `404` `SCHOOLCAMP_001`
- 총원이 8명 초과, 또는 담당 선생님/팀원 정보 형식이 잘못됨(정확히 하나씩이 아님, 지정한
  `teacherUserId`가 `TEACHER` 역할이 아님) → `400` `SCHOOLCAMP_004`
- `additionalMembers`에 존재하지 않는 `studentUserId`나 중복 항목(자기 자신 포함) 포함 →
  `400` `SCHOOLCAMP_008`
- 대표 신청자 본인 또는 `additionalMembers`의 학생 중 이번 달에 이미 참여(대표/팀원 구분
  없이)한 사람이 포함됨 → `409` `SCHOOLCAMP_003`
- 이미 다른 신청이 이 날짜를 선점함(선착순 마감, 팀 인원수와 무관) → `409` `SCHOOLCAMP_002`
- `STUDENT`가 아닌 계정 호출 → `403` `COMMON_003`(`@PreAuthorize` 거부, 기존 공통 처리)

> ⚠️ **6번(월 중복 확인)과 7번(저장) 사이에는 여전히 이론상 레이스가 있다**(같은 학생이 이번
> 달 두 세션에 동시에 참여 시도) — 마스터 기획서 판단 그대로, `outing`과 동일한 이유로
> 애플리케이션 체크만으로 충분하다고 보고 이 이슈에서는 별도 처리하지 않는다.

### `GET /api/v1/school-camps?month=yyyyMM` — 캘린더 응답 보강 (#67 기존 엔드포인트 수정)
새 엔드포인트가 아니라, #67이 항상 `null`로 남겨둔 두 필드를 이 이슈에서 채운다.

**변경 전(#67)**: `taken_at`이 있는 세션도 `teacherDisplayName`/`applicantDisplayName`이
항상 `null`.

**변경 후(이 이슈)**: `taken_at`이 있는 세션마다 그 세션의 활성 신청(`session_id` +
`cancelled_at IS NULL`, 정확히 1건)을 `SchoolCampApplicationRepository
.findBySessionIdAndCancelledAtIsNull`로 조회해
- `teacherDisplayName` = `teacherUser`가 있으면 `teacherUser.getGbsw().getName()`, 아니면
  `teacherName` 그대로
- `applicantDisplayName` = `GbswUtils.studentNumber(applicant.getGbsw()) +
  applicant.getGbsw().getName()`

로 채운다. `SchoolCampService.getCalendar`의 세션별 매핑 로직만 바뀌고, 컨트롤러/엔드포인트
경로/응답 스키마는 #67 그대로라 계약 변경이 아니다(값이 채워질 뿐 필드 자체는 이미 있었음).

## 영향 받는 기존 코드
- 신규: `entity/SchoolCampApplication`, `entity/SchoolCampMember`,
  `repository/SchoolCampApplicationRepository`, `repository/SchoolCampMemberRepository`,
  `service/SchoolCampSessionClaimService`(claim/release 전용, `REQUIRES_NEW`),
  `dto/SchoolCampApplyRequest`, `dto/SchoolCampMemberRequest`,
  `dto/SchoolCampApplicationResponse`, `dto/SchoolCampMemberResponse`,
  `gbsw/utils/GbswUtils`
- 수정: `SchoolCampSessionRepository`(`claim`/`release` 메서드 추가), `SchoolCampService`
  (`applyToCamp` 추가 — `SchoolCampSessionClaimService`를 주입받아 호출, `getCalendar`의
  이름 필드 계산 로직 교체), `SchoolCampController`(`POST /{sessionId}/applications` 추가),
  `SchoolCampErrorCode`(`001`/`002`/`003`/`004`/`008` 추가), `AuthService`
  (`generateStudentDefaultName`이 `GbswUtils.studentNumber` 사용하도록 리팩터링, 동작 변화
  없음)
- `V12__add_schoolcamp_application.sql`(신규 마이그레이션)
- `SecurityConfig`(수정 없음): `/api/v1/school-camps/**`가 이미 #67에서 인증 요구로
  등록되어 있고, `@PreAuthorize`는 `outing`에서 활성화된 `@EnableMethodSecurity`를 재사용

## 리스크 및 고려사항
- **API 설계 6원칙 체크**:
  1. 한 가지를 잘하기 — 신청 엔드포인트 1개(+ 기존 캘린더 응답 보강)로 범위가 좁다. 준수.
  2. 빠르게 시작 — 요청/응답 예시 포함. 준수.
  3. 직관적 일관성 — 기존 `SCHOOLCAMP_NNN` 코드 컨벤션, `ApiResponse<T>` 그대로.
  4. 의미 있는 오류 — 원인별 코드 분리(`001`/`002`/`003`/`004`/`008`), `outing`처럼 선착순
     마감(`002`)과 형식 오류(`004`)를 구분해 클라이언트가 재시도 가능 여부를 판단할 수 있게 함.
  5. 확장성/성능 — 세션 점유는 `@Modifying` 조건부 `UPDATE` 한 번으로 락 경합을 최소화(별도
     비관적 락/재시도 루프 불필요), 그 UPDATE는 별도 트랜잭션으로 즉시 커밋해 락 보유 시간을
     최소화한다(아래 "커넥션 풀 경합" 절 참고). 월 중복 확인 쿼리는 후보 학생 수(최대 8명)로
     상한이 있어 N+1 우려 없음.
  6. 하위 호환성 — 신규 엔드포인트 1개 + 기존 캘린더 응답 필드값 채움(스키마 변경 아님)이라
     해당 없음.
- **동시성의 두 층위를 섞지 않는다**(마스터 기획서 "공통 구현 고려사항" 그대로): 세션
  점유(`taken_at`)는 조건부 `UPDATE`로, 월 중복 확인은 일반 `SELECT`로 처리한다 — 성격이
  다른 두 동시성 문제를 한 로직에 섞지 않는다.
- **`@Modifying` 조건부 `UPDATE`와 `Propagation.REQUIRES_NEW`가 이 프로젝트에서 처음 쓰이는
  패턴**이다(기존 코드는 `outing`의 낙관적 락(`@Version`)/비관적 락(`findByIdForUpdate`)만
  사용, 둘 다 트랜잭션 전파 레벨을 건드리지 않는다). 리뷰 시 특히 꼼꼼히 볼 지점 두 가지:
  (1) `claim`/`release` 쿼리는 `@Transactional` 안에서 호출돼야 영향받은 행 수를 정확히
  반환한다. (2) `SchoolCampSessionClaimService`는 `SchoolCampService`와 **다른 빈**이어야
  `REQUIRES_NEW`가 실제로 적용된다 — 같은 클래스 안에 메서드로 넣고 `this`로 호출하면 Spring
  AOP 프록시를 우회해 조용히 무시된다(컴파일 에러가 안 남).
- **알림 발송 실패는 그대로 예외 전파**(신청 저장과 같은 트랜잭션) — `NotificationService`
  기획서의 기존 정책 그대로 재사용. 신청 자체가 성사됐는데 알림만 실패해 롤백되는 게 이상해
  보일 수 있지만, 이미 승인된 프로젝트 정책이라 이 이슈에서 바꾸지 않는다.
- **`teacherName` 자유 입력에 대한 실명 검증(오타/장난 문자열)은 하지 않는다** — 마스터
  기획서 "정책 가정"에 담당 선생님 승인 절차가 없다고 이미 확정돼 있고, 이 이슈도 그 전제를
  그대로 따른다.
- **트래픽 집중 대응(확정)**: 등록 오픈 순간 한 세션에 다량의 동시 요청이 몰릴 수 있다는
  전제로, "형식 검증 → claim → 무거운 검증" 순서를 확정했다(위 "구현 로직" 참고). claim에서
  탈락하는 대다수 요청은 짧은 트랜잭션 하나로 끝나 DB 부하가 늘지 않는다. 트레이드오프:
  claim에 성공했지만 이후 검증(4~6번)에서 결국 탈락하는 당첨자가 있으면, 그 검증과 명시적
  `release` 호출이 끝날 때까지의 짧은 시간 동안은 실제로는 비어있게 될 세션이 다른 정상
  요청에게 "이미 마감"으로 보일 수 있다 — 그 창은 한 요청의 검증 소요 시간(수 ms~수십 ms)
  수준으로 짧고, 검증 자체가 대부분 통과하는 정상 흐름을 전제하면 감수 가능한 수준으로
  판단한다.
- **커넥션 풀 경합 대응 — claim을 별도 트랜잭션으로 분리(확정, 2026-08-19)**: 재학생 300명
  규모에서 한 세션에 100명 이상이 동시에 신청을 시도할 수 있다는 전제를 반영했다. 이
  프로젝트 `application.yml`은 HikariCP `maximum-pool-size`를 따로 안 정해 **기본값(10)을
  그대로 쓴다.** 만약 claim이 4~8번(선생님 역할 확인·팀원 배치 조회·월 중복 조회·저장·알림
  발송, `NotificationService.send`는 FCM 같은 외부 호출 없이 로컬 DB INSERT뿐이라 합쳐서
  DB 왕복 12~20회 안팎)과 **같은 트랜잭션**에 있었다면(검토 초안), 당첨자가 이 구간 내내
  세션 행의 배타 락을 쥐고 있게 된다. 그 순간 같은 세션에 몰린 나머지 요청 중 커넥션을 먼저
  잡은 것들은 그 행 잠금 때문에 블로킹되고, 나머지는 커넥션 자체를 못 얻어 HikariCP 대기열에
  쌓인다 — 풀이 10개뿐이라 100명 규모의 순간 경합만으로 풀 전체가 묶일 수 있고, 이 풀은
  스쿨캠핑 API 전용이 아니라 서버 전체가 공유하므로 그 순간 로그인·외출 신청 등 무관한 다른
  요청까지 같이 지연된다. **claim을 `SchoolCampSessionClaimService`의 별도
  `REQUIRES_NEW` 트랜잭션으로 즉시 커밋**하면, 당첨자가 행 잠금을 쥐는 시간이 "UPDATE 한
  문장" 수준(수 ms)으로 줄어 이 문제가 사라진다. 트레이드오프: "검증 실패 시 트랜잭션
  롤백만으로 자동 반환"이라는 단순함을 잃고, 4~8번 실패 시 `release`를 명시적으로 호출해야
  한다(위 "구현 로직"의 ⚠️ 콜아웃과 "잔여 리스크" 참고) — 호출을 빠뜨리면 세션이 영구히
  막힌 채로 남는 위험이 생기므로 리뷰 시 모든 실패 경로에서 `release`가 호출되는지 반드시
  확인한다.
  - **참고(이 이슈 범위 밖)**: HikariCP 풀 크기를 기본값보다 올리는 것도 값싼 보완책이지만,
    한 세션 행에 대한 쓰기 직렬화 자체는 풀 크기와 무관하게 그대로 남는다(MySQL이 같은
    행에 대한 쓰기를 하나씩만 처리하기 때문) — 풀을 키우면 그 경합 중에도 다른 기능이 쓸
    여유 커넥션이 남는다는 정도의 효과이지, claim 트랜잭션 분리를 대체하지 않는다. 필요성이
    확인되면 별도 이슈로 다룬다.
- **프론트엔드 참고사항(백엔드 구현 범위 아님, 가이드로만 기록)**: 정합성은 백엔드 claim이
  전적으로 보장하므로, 프론트가 실시간이 아니어도 이중 신청 같은 오류는 나지 않는다 — 다만
  "이미 마감된 날짜에 신청 버튼을 눌러 실망하는 빈도"를 낮추려면 신청 화면이 열려있는 동안
  캘린더(`GET /api/v1/school-camps?month=`)를 **짧은 주기(2~5초) 폴링**으로 재조회하는 걸
  권장한다. SSE/WebSocket 같은 실시간 push는 이 프로젝트에 아직 그 인프라가 전혀 없어
  (알림 도메인 기획서의 FCM도 "2단계" 미래 확장으로만 존재) 이 이슈 하나 때문에 새로 들이지
  않는다 — 이 패턴이 다른 도메인에서도 반복되면 그때 공용 인프라로 검토한다. 실제 채택 여부는
  프론트 팀 판단이며, 백엔드는 폴링이든 push든 별도 대응이 필요 없다(매 요청이 그냥
  `GET`/`POST` 하나일 뿐이라 이미 지원됨).
- 세션 점유(claim)를 별도 트랜잭션의 UPDATE 한 문장으로 하는 것과 `SELECT ... FOR
  UPDATE`(비관적 락)로 세션 조회 시점부터 잠그는 것의 차이: 비관적 락은 4~8번 검증 전체가
  끝날 때까지 락을 쥔 트랜잭션이 끝나지 않으므로, 그 세션을 노리는 나머지 요청은 그 전체
  구간 동안 커넥션을 붙든 채 대기한다. 이 설계의 claim은 그 자체가 독립된 짧은
  트랜잭션이라, 커밋 이후 도착하는 요청은 즉시 성공/실패가 갈려 대기가 거의 없다 — 다만
  claim이 아직 커밋되기 전 그 찰나(단일 UPDATE 문 실행 시간)에 정확히 겹친 요청은 MySQL의
  아주 짧은 행 잠금 대기를 거친다. 요약하면 대기 구간이 "요청 전체 처리 시간"에서
  "UPDATE 한 문장의 실행 시간"으로 줄어드는 것이지, 대기가 원천적으로 사라지는 것은
  아니다.

## 테스트
- `POST /api/v1/school-camps/{sessionId}/applications`:
  - 정상 신청(가입 학생 팀원 + 자유 입력 팀원 혼합), 대표 신청자만(팀원 0명) 신청
  - 존재하지 않는 세션 → `404`
  - `teacherUserId`/`teacherName` 둘 다 없음/둘 다 있음 → `400`
  - `teacherUserId`가 `TEACHER` 역할이 아님 → `400`
  - 팀원 9명(대표 포함) 초과 → `400`
  - `additionalMembers`에 존재하지 않는 `studentUserId` → `400`
  - `additionalMembers`에 같은 `studentUserId` 중복, 또는 대표 신청자 본인 포함 → `400`
  - 이번 달에 이미 참여(대표로 신청한 적 있음 / 팀원으로만 참여한 적 있음) → `409`
  - 이미 다른 신청이 그 세션을 선점 → `409`
  - **동시 신청 레이스**: 같은 세션에 두 스레드(또는 두 트랜잭션)가 동시에
    `SchoolCampSessionClaimService.claim` 호출 시 정확히 하나만 성공(`true`)하고 나머지는
    `false`를 반환하는지 검증
  - **claim 실패 시 조기 종료**: 세션이 이미 점유된 상태로 신청하면, 담당 선생님 역할
    조회/팀원 존재 조회/월 중복 조회가 전혀 호출되지 않는지 목(mock) 검증(순서 재배치가
    실제로 지켜지는지 확인)
  - **claim은 즉시 커밋된다**: `claim` 호출 직후(같은 요청의 나머지 로직이 끝나기 전) 별도
    커넥션/트랜잭션으로 세션을 조회하면 이미 `taken_at`이 채워져 있는지 확인(`REQUIRES_NEW`가
    실제로 별도 트랜잭션으로 즉시 커밋하는지 검증 — `@SpringBootTest`/`@DataJpaTest`
    슬라이스 필요)
  - **claim 이후 검증 실패 시 명시적 반환**: claim에는 성공했지만 이후 검증(예: 존재하지
    않는 `teacherUserId`)에서 실패하면, `SchoolCampSessionClaimService.release`가
    호출되어 세션의 `taken_at`이 다시 `null`로 돌아오는지 검증(통합 테스트 레벨 — 이제는
    트랜잭션 롤백이 아니라 명시적 호출이므로, `release` 호출 자체를 목 검증하거나 DB 상태를
    직접 재조회해 확인)
  - `STUDENT`가 아닌 계정 호출 → `403`
  - 가입된 팀원에게 초대 알림이 저장되는지(`NotificationService.send` 호출 검증)
- `GbswUtils.studentNumber`: 정상 케이스(반이 1자리 확인), `AuthService.generateStudentDefaultName`
  기존 테스트가 리팩터링 후에도 그대로 통과하는지(회귀 확인)
- `GET /api/v1/school-camps?month=`: `taken_at` 있는 세션의 `teacherDisplayName`(가입
  선생님/자유 입력 두 케이스)/`applicantDisplayName`이 정확히 채워지는지(#67 QA에서 "이후
  재검증 필요"로 남겨둔 항목)
