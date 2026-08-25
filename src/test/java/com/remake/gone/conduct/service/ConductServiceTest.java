package com.remake.gone.conduct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.common.response.PageResponse;
import com.remake.gone.conduct.config.ConductProperties;
import com.remake.gone.conduct.dto.ConductAmendRequest;
import com.remake.gone.conduct.dto.ConductCancelRequest;
import com.remake.gone.conduct.dto.ConductCategoryResponse;
import com.remake.gone.conduct.dto.ConductGrantRequest;
import com.remake.gone.conduct.dto.ConductRecordResponse;
import com.remake.gone.conduct.dto.ConductStudentRecordResponse;
import com.remake.gone.conduct.dto.ConductSummaryResponse;
import com.remake.gone.conduct.entity.ConductCategory;
import com.remake.gone.conduct.entity.ConductRecord;
import com.remake.gone.conduct.enums.ConductStatus;
import com.remake.gone.conduct.enums.ConductType;
import com.remake.gone.conduct.exception.ConductErrorCode;
import com.remake.gone.conduct.repository.ConductCategoryRepository;
import com.remake.gone.conduct.repository.ConductRecordRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * {@link ConductService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class ConductServiceTest {

  @Mock
  private ConductCategoryRepository conductCategoryRepository;

  @Mock
  private ConductRecordRepository conductRecordRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private UserRoleRepository userRoleRepository;

  @Mock
  private ConductProperties conductProperties;

  @InjectMocks
  private ConductService conductService;

  @Nested
  @DisplayName("getCategories")
  class GetCategories {

    @Test
    @DisplayName("활성 카테고리 목록을 id·label·type·points가 정확히 매핑된 DTO로 변환해 반환한다")
    void returnsActiveCategoriesAsMappedDto() {
      ConductCategory merit = ConductCategory.builder()
          .id(1L)
          .label("봉사활동으로 교내 청소를 열심히 한 학생")
          .type(ConductType.MERIT)
          .points(2)
          .build();
      ConductCategory demerit = ConductCategory.builder()
          .id(19L)
          .label("용의 규정을 위반한 학생(염색)")
          .type(ConductType.DEMERIT)
          .points(-5)
          .build();
      given(conductCategoryRepository.findByActiveTrueOrderByIdAsc())
          .willReturn(List.of(merit, demerit));

      List<ConductCategoryResponse> result = conductService.getCategories();

      assertThat(result).hasSize(2);
      assertThat(result.get(0).id()).isEqualTo(1L);
      assertThat(result.get(0).label()).isEqualTo("봉사활동으로 교내 청소를 열심히 한 학생");
      assertThat(result.get(0).type()).isEqualTo(ConductType.MERIT);
      assertThat(result.get(0).points()).isEqualTo(2);
      assertThat(result.get(1).id()).isEqualTo(19L);
      assertThat(result.get(1).label()).isEqualTo("용의 규정을 위반한 학생(염색)");
      assertThat(result.get(1).type()).isEqualTo(ConductType.DEMERIT);
      assertThat(result.get(1).points()).isEqualTo(-5);
    }

    @Test
    @DisplayName("활성 카테고리가 없으면 빈 목록을 반환한다")
    void returnsEmptyListWhenNoActiveCategories() {
      given(conductCategoryRepository.findByActiveTrueOrderByIdAsc())
          .willReturn(List.of());

      List<ConductCategoryResponse> result = conductService.getCategories();

      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("grantConduct")
  class GrantConduct {

    private final Long teacherUserId = 42L;
    private final Long studentUserId = 101L;
    private final Long categoryId = 5L;

    private ConductCategory demeritsCategory() {
      return ConductCategory.builder()
          .id(categoryId)
          .label("지각")
          .type(ConductType.DEMERIT)
          .points(-1)
          .active(true)
          .build();
    }

    private User student() {
      return User.builder().id(studentUserId).name("길동이").build();
    }

    private User teacher() {
      return User.builder().id(teacherUserId).name("김선생").build();
    }

    @Test
    @DisplayName("카테고리·학생·역할이 모두 유효하면 ConductRecord를 저장하고 응답 DTO를 반환한다")
    void savesRecordAndReturnsDto() {
      ConductCategory category = demeritsCategory();
      User studentUser = student();
      User teacherUser = teacher();
      ConductGrantRequest request =
          new ConductGrantRequest(studentUserId, categoryId, "3교시 10분 지각");

      given(conductCategoryRepository.findById(categoryId)).willReturn(Optional.of(category));
      given(userRepository.findById(studentUserId)).willReturn(Optional.of(studentUser));
      given(userRoleRepository.findRoleCodesByUserId(studentUserId)).willReturn(List.of("STUDENT"));
      given(userRepository.findById(teacherUserId)).willReturn(Optional.of(teacherUser));
      given(conductRecordRepository.save(any(ConductRecord.class))).willAnswer(inv -> {
        ConductRecord r = inv.getArgument(0);
        return ConductRecord.builder()
            .id(501L)
            .student(r.getStudent())
            .teacher(r.getTeacher())
            .category(r.getCategory())
            .type(r.getType())
            .points(r.getPoints())
            .detail(r.getDetail())
            .status(ConductStatus.ACTIVE)
            .version(0L)
            .build();
      });

      ConductRecordResponse result = conductService.grantConduct(teacherUserId, request);

      assertThat(result.id()).isEqualTo(501L);
      assertThat(result.studentUserId()).isEqualTo(studentUserId);
      assertThat(result.studentNickname()).isEqualTo("길동이");
      assertThat(result.teacherUserId()).isEqualTo(teacherUserId);
      assertThat(result.teacherNickname()).isEqualTo("김선생");
      assertThat(result.categoryId()).isEqualTo(categoryId);
      assertThat(result.categoryLabel()).isEqualTo("지각");
      assertThat(result.type()).isEqualTo(ConductType.DEMERIT);
      assertThat(result.points()).isEqualTo(-1);
      assertThat(result.detail()).isEqualTo("3교시 10분 지각");
      assertThat(result.status()).isEqualTo(ConductStatus.ACTIVE);
    }

    @Test
    @DisplayName("비활성 카테고리이면 CONDUCT_004 예외를 던진다")
    void throwsWhenCategoryInactive() {
      ConductCategory inactive = ConductCategory.builder()
          .id(categoryId).label("지각").type(ConductType.DEMERIT).points(-1).active(false).build();
      ConductGrantRequest request = new ConductGrantRequest(studentUserId, categoryId, null);

      given(conductCategoryRepository.findById(categoryId)).willReturn(Optional.of(inactive));

      assertThatThrownBy(() -> conductService.grantConduct(teacherUserId, request))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.CATEGORY_NOT_FOUND_OR_INACTIVE);
    }

    @Test
    @DisplayName("존재하지 않는 categoryId이면 CONDUCT_004 예외를 던진다")
    void throwsWhenCategoryNotFound() {
      ConductGrantRequest request = new ConductGrantRequest(studentUserId, 999L, null);

      given(conductCategoryRepository.findById(999L)).willReturn(Optional.empty());

      assertThatThrownBy(() -> conductService.grantConduct(teacherUserId, request))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.CATEGORY_NOT_FOUND_OR_INACTIVE);
    }

    @Test
    @DisplayName("존재하지 않는 studentUserId이면 CONDUCT_005 예외를 던진다")
    void throwsWhenStudentNotFound() {
      ConductCategory category = demeritsCategory();
      ConductGrantRequest request = new ConductGrantRequest(999L, categoryId, null);

      given(conductCategoryRepository.findById(categoryId)).willReturn(Optional.of(category));
      given(userRepository.findById(999L)).willReturn(Optional.empty());

      assertThatThrownBy(() -> conductService.grantConduct(teacherUserId, request))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.STUDENT_NOT_FOUND);
    }

    @Test
    @DisplayName("대상 사용자가 STUDENT 역할이 아니면 CONDUCT_006 예외를 던진다")
    void throwsWhenNotStudentRole() {
      ConductCategory category = demeritsCategory();
      User teacherUser = teacher();
      ConductGrantRequest request = new ConductGrantRequest(studentUserId, categoryId, null);

      given(conductCategoryRepository.findById(categoryId)).willReturn(Optional.of(category));
      given(userRepository.findById(studentUserId)).willReturn(Optional.of(teacherUser));
      given(userRoleRepository.findRoleCodesByUserId(studentUserId)).willReturn(List.of("TEACHER"));

      assertThatThrownBy(() -> conductService.grantConduct(teacherUserId, request))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.NOT_STUDENT_ROLE);
    }
  }

  @Nested
  @DisplayName("amendConduct")
  class AmendConduct {

    private final Long teacherUserId = 42L;
    private final Long otherTeacherUserId = 99L;
    private final Long studentUserId = 101L;
    private final Long recordId = 501L;
    private final Long categoryId = 5L;
    private final Long newCategoryId = 6L;

    private ConductCategory demeritsCategory() {
      return ConductCategory.builder()
          .id(categoryId)
          .label("지각")
          .type(ConductType.DEMERIT)
          .points(-1)
          .active(true)
          .build();
    }

    private ConductCategory newDemeritsCategory() {
      return ConductCategory.builder()
          .id(newCategoryId)
          .label("무단조퇴")
          .type(ConductType.DEMERIT)
          .points(-3)
          .active(true)
          .build();
    }

    private ConductRecord activeRecord(ConductCategory category) {
      User student = User.builder().id(studentUserId).name("길동이").build();
      User teacher = User.builder().id(teacherUserId).name("김선생").build();
      return ConductRecord.builder()
          .id(recordId)
          .student(student)
          .teacher(teacher)
          .category(category)
          .type(category.getType())
          .points(category.getPoints())
          .detail("3교시 10분 지각")
          .status(ConductStatus.ACTIVE)
          .version(0L)
          .build();
    }

    @Test
    @DisplayName("categoryId와 detail을 모두 정정하면 category·type·points·detail이 갱신된다")
    void amendsBothCategoryAndDetail() {
      ConductRecord record = activeRecord(demeritsCategory());
      ConductCategory newCategory = newDemeritsCategory();
      ConductAmendRequest request = new ConductAmendRequest(newCategoryId, "3교시 이후 조퇴");

      given(conductRecordRepository.findById(recordId)).willReturn(Optional.of(record));
      given(userRoleRepository.findRoleCodesByUserId(teacherUserId))
          .willReturn(List.of("TEACHER"));
      given(conductCategoryRepository.findById(newCategoryId))
          .willReturn(Optional.of(newCategory));

      ConductRecordResponse result = conductService.amendConduct(teacherUserId, recordId, request);

      assertThat(result.categoryId()).isEqualTo(newCategoryId);
      assertThat(result.categoryLabel()).isEqualTo("무단조퇴");
      assertThat(result.type()).isEqualTo(ConductType.DEMERIT);
      assertThat(result.points()).isEqualTo(-3);
      assertThat(result.detail()).isEqualTo("3교시 이후 조퇴");
    }

    @Test
    @DisplayName("detail만 정정하면 category·type·points는 변경되지 않는다")
    void amendsDetailOnly() {
      ConductRecord record = activeRecord(demeritsCategory());
      ConductAmendRequest request = new ConductAmendRequest(null, "수업 10분 전 지각");

      given(conductRecordRepository.findById(recordId)).willReturn(Optional.of(record));
      given(userRoleRepository.findRoleCodesByUserId(teacherUserId))
          .willReturn(List.of("TEACHER"));

      ConductRecordResponse result = conductService.amendConduct(teacherUserId, recordId, request);

      assertThat(result.categoryId()).isEqualTo(categoryId);
      assertThat(result.points()).isEqualTo(-1);
      assertThat(result.detail()).isEqualTo("수업 10분 전 지각");
    }

    @Test
    @DisplayName("ADMIN은 본인이 부여하지 않은 기록도 정정할 수 있다")
    void adminCanAmendAnyRecord() {
      Long adminUserId = 999L;
      ConductRecord record = activeRecord(demeritsCategory());
      ConductAmendRequest request = new ConductAmendRequest(null, "ADMIN 수정");

      given(conductRecordRepository.findById(recordId)).willReturn(Optional.of(record));
      given(userRoleRepository.findRoleCodesByUserId(adminUserId))
          .willReturn(List.of("ADMIN"));

      ConductRecordResponse result = conductService.amendConduct(adminUserId, recordId, request);

      assertThat(result.detail()).isEqualTo("ADMIN 수정");
    }

    @Test
    @DisplayName("categoryId·detail 둘 다 null이면 COMMON_001 예외를 던진다")
    void throwsWhenBothFieldsNull() {
      assertThatThrownBy(() -> conductService.amendConduct(
          teacherUserId, recordId, new ConductAmendRequest(null, null)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(CommonErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("존재하지 않는 recordId이면 CONDUCT_001 예외를 던진다")
    void throwsWhenRecordNotFound() {
      given(conductRecordRepository.findById(recordId)).willReturn(Optional.empty());

      assertThatThrownBy(() -> conductService.amendConduct(
          teacherUserId, recordId, new ConductAmendRequest(null, "변경")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.RECORD_NOT_FOUND);
    }

    @Test
    @DisplayName("본인이 부여하지 않은 기록을 TEACHER가 정정하면 CONDUCT_002 예외를 던진다")
    void throwsWhenNotRecordOwner() {
      ConductRecord record = activeRecord(demeritsCategory());

      given(conductRecordRepository.findById(recordId)).willReturn(Optional.of(record));
      given(userRoleRepository.findRoleCodesByUserId(otherTeacherUserId))
          .willReturn(List.of("TEACHER"));

      assertThatThrownBy(() -> conductService.amendConduct(
          otherTeacherUserId, recordId, new ConductAmendRequest(null, "수정 시도")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.NOT_RECORD_OWNER);
    }

    @Test
    @DisplayName("이미 취소된 기록이면 CONDUCT_003 예외를 던진다")
    void throwsWhenAlreadyCanceled() {
      ConductRecord canceledRecord = ConductRecord.builder()
          .id(recordId)
          .student(User.builder().id(studentUserId).name("길동이").build())
          .teacher(User.builder().id(teacherUserId).name("김선생").build())
          .category(demeritsCategory())
          .type(ConductType.DEMERIT)
          .points(-1)
          .status(ConductStatus.CANCELED)
          .version(1L)
          .build();

      given(conductRecordRepository.findById(recordId)).willReturn(Optional.of(canceledRecord));
      given(userRoleRepository.findRoleCodesByUserId(teacherUserId))
          .willReturn(List.of("TEACHER"));

      assertThatThrownBy(() -> conductService.amendConduct(
          teacherUserId, recordId, new ConductAmendRequest(null, "수정 시도")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.ALREADY_CANCELED);
    }

    @Test
    @DisplayName("비활성 categoryId로 정정하면 CONDUCT_004 예외를 던진다")
    void throwsWhenNewCategoryInactive() {
      ConductRecord record = activeRecord(demeritsCategory());
      ConductCategory inactiveCategory = ConductCategory.builder()
          .id(newCategoryId).label("무단조퇴").type(ConductType.DEMERIT).points(-3).active(false)
          .build();

      given(conductRecordRepository.findById(recordId)).willReturn(Optional.of(record));
      given(userRoleRepository.findRoleCodesByUserId(teacherUserId))
          .willReturn(List.of("TEACHER"));
      given(conductCategoryRepository.findById(newCategoryId))
          .willReturn(Optional.of(inactiveCategory));

      assertThatThrownBy(() -> conductService.amendConduct(
          teacherUserId, recordId, new ConductAmendRequest(newCategoryId, null)))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.CATEGORY_NOT_FOUND_OR_INACTIVE);
    }
  }

  @Nested
  @DisplayName("cancelConduct")
  class CancelConduct {

    private final Long teacherUserId = 42L;
    private final Long otherTeacherUserId = 99L;
    private final Long studentUserId = 101L;
    private final Long recordId = 501L;
    private final Long categoryId = 5L;

    private ConductCategory demeritsCategory() {
      return ConductCategory.builder()
          .id(categoryId).label("지각").type(ConductType.DEMERIT).points(-1).active(true).build();
    }

    private ConductRecord activeRecord() {
      User student = User.builder().id(studentUserId).name("길동이").build();
      User teacher = User.builder().id(teacherUserId).name("김선생").build();
      return ConductRecord.builder()
          .id(recordId)
          .student(student)
          .teacher(teacher)
          .category(demeritsCategory())
          .type(ConductType.DEMERIT)
          .points(-1)
          .detail("3교시 10분 지각")
          .status(ConductStatus.ACTIVE)
          .version(0L)
          .build();
    }

    @Test
    @DisplayName("정상 취소 시 status가 CANCELED로 변경되고 취소 사유가 저장된다")
    void cancelsRecordSuccessfully() {
      ConductRecord record = activeRecord();
      User teacherUser = User.builder().id(teacherUserId).name("김선생").build();
      given(conductRecordRepository.findById(recordId)).willReturn(Optional.of(record));
      given(userRoleRepository.findRoleCodesByUserId(teacherUserId))
          .willReturn(List.of("TEACHER"));
      given(userRepository.findById(teacherUserId)).willReturn(Optional.of(teacherUser));

      ConductCancelRequest request = new ConductCancelRequest("오인 부여 확인됨");
      conductService.cancelConduct(teacherUserId, recordId, request);

      assertThat(record.getStatus()).isEqualTo(ConductStatus.CANCELED);
      assertThat(record.getCancelReason()).isEqualTo("오인 부여 확인됨");
      assertThat(record.getCanceledBy()).isEqualTo(teacherUser);
      assertThat(record.getCanceledAt()).isNotNull();
    }

    @Test
    @DisplayName("ADMIN은 본인이 부여하지 않은 기록도 취소할 수 있다")
    void adminCanCancelAnyRecord() {
      Long adminUserId = 999L;
      ConductRecord record = activeRecord();
      User adminUser = User.builder().id(adminUserId).name("관리자").build();
      ConductCancelRequest request = new ConductCancelRequest("ADMIN 취소");

      given(conductRecordRepository.findById(recordId)).willReturn(Optional.of(record));
      given(userRoleRepository.findRoleCodesByUserId(adminUserId))
          .willReturn(List.of("ADMIN"));
      given(userRepository.findById(adminUserId)).willReturn(Optional.of(adminUser));

      ConductRecordResponse result = conductService.cancelConduct(adminUserId, recordId, request);

      assertThat(result.status()).isEqualTo(ConductStatus.CANCELED);
    }

    @Test
    @DisplayName("존재하지 않는 recordId이면 CONDUCT_001 예외를 던진다")
    void throwsWhenRecordNotFound() {
      given(conductRecordRepository.findById(recordId)).willReturn(Optional.empty());

      assertThatThrownBy(() -> conductService.cancelConduct(
          teacherUserId, recordId, new ConductCancelRequest("취소 사유")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.RECORD_NOT_FOUND);
    }

    @Test
    @DisplayName("본인이 부여하지 않은 기록을 TEACHER가 취소하면 CONDUCT_002 예외를 던진다")
    void throwsWhenNotRecordOwner() {
      ConductRecord record = activeRecord();

      given(conductRecordRepository.findById(recordId)).willReturn(Optional.of(record));
      given(userRoleRepository.findRoleCodesByUserId(otherTeacherUserId))
          .willReturn(List.of("TEACHER"));

      assertThatThrownBy(() -> conductService.cancelConduct(
          otherTeacherUserId, recordId, new ConductCancelRequest("취소 시도")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.NOT_RECORD_OWNER);
    }

    @Test
    @DisplayName("이미 취소된 기록이면 CONDUCT_003 예외를 던진다")
    void throwsWhenAlreadyCanceled() {
      ConductRecord canceledRecord = ConductRecord.builder()
          .id(recordId)
          .student(User.builder().id(studentUserId).name("길동이").build())
          .teacher(User.builder().id(teacherUserId).name("김선생").build())
          .category(demeritsCategory())
          .type(ConductType.DEMERIT)
          .points(-1)
          .status(ConductStatus.CANCELED)
          .version(1L)
          .build();

      given(conductRecordRepository.findById(recordId)).willReturn(Optional.of(canceledRecord));
      given(userRoleRepository.findRoleCodesByUserId(teacherUserId))
          .willReturn(List.of("TEACHER"));

      assertThatThrownBy(() -> conductService.cancelConduct(
          teacherUserId, recordId, new ConductCancelRequest("재취소 시도")))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.ALREADY_CANCELED);
    }
  }

  @Nested
  @DisplayName("getStudentSummary")
  class GetStudentSummary {

    private final Long studentUserId = 101L;

    @Test
    @DisplayName("ACTIVE 기록 합산으로 totalMeritPoints, totalDemeritPoints, netScore를 계산한다")
    void calculatesSummaryFromActiveRecords() {
      given(conductRecordRepository.sumPointsByStudentAndType(
          studentUserId, ConductType.MERIT, ConductStatus.ACTIVE)).willReturn(6);
      given(conductRecordRepository.sumPointsByStudentAndType(
          studentUserId, ConductType.DEMERIT, ConductStatus.ACTIVE)).willReturn(-4);
      given(conductProperties.demeritThreshold()).willReturn(10);

      ConductSummaryResponse result = conductService.getStudentSummary(studentUserId);

      assertThat(result.totalMeritPoints()).isEqualTo(6);
      assertThat(result.totalDemeritPoints()).isEqualTo(-4);
      assertThat(result.netScore()).isEqualTo(2);
      assertThat(result.demeritThreshold()).isEqualTo(10);
      assertThat(result.overDemeritThreshold()).isFalse();
    }

    @Test
    @DisplayName("누적 벌점 절댓값이 임계치 이상이면 overDemeritThreshold가 true다")
    void flagsOverThresholdWhenDemeritExceedsLimit() {
      given(conductRecordRepository.sumPointsByStudentAndType(
          studentUserId, ConductType.MERIT, ConductStatus.ACTIVE)).willReturn(0);
      given(conductRecordRepository.sumPointsByStudentAndType(
          studentUserId, ConductType.DEMERIT, ConductStatus.ACTIVE)).willReturn(-10);
      given(conductProperties.demeritThreshold()).willReturn(10);

      ConductSummaryResponse result = conductService.getStudentSummary(studentUserId);

      assertThat(result.overDemeritThreshold()).isTrue();
    }

    @Test
    @DisplayName("기록이 없으면 모든 점수가 0이다")
    void returnsZeroWhenNoRecords() {
      given(conductRecordRepository.sumPointsByStudentAndType(
          studentUserId, ConductType.MERIT, ConductStatus.ACTIVE)).willReturn(0);
      given(conductRecordRepository.sumPointsByStudentAndType(
          studentUserId, ConductType.DEMERIT, ConductStatus.ACTIVE)).willReturn(0);
      given(conductProperties.demeritThreshold()).willReturn(10);

      ConductSummaryResponse result = conductService.getStudentSummary(studentUserId);

      assertThat(result.totalMeritPoints()).isEqualTo(0);
      assertThat(result.totalDemeritPoints()).isEqualTo(0);
      assertThat(result.netScore()).isEqualTo(0);
      assertThat(result.overDemeritThreshold()).isFalse();
    }
  }

  @Nested
  @DisplayName("getStudentRecords")
  class GetStudentRecords {

    private final Long studentUserId = 101L;

    private ConductRecord sampleRecord() {
      return ConductRecord.builder()
          .id(501L)
          .student(User.builder().id(studentUserId).name("길동이").build())
          .teacher(User.builder().id(42L).name("김선생").build())
          .category(ConductCategory.builder()
              .id(5L).label("지각").type(ConductType.DEMERIT).points(-1).build())
          .type(ConductType.DEMERIT)
          .points(-1)
          .detail("3교시 10분 지각")
          .status(ConductStatus.ACTIVE)
          .createdAt(LocalDateTime.of(2026, 8, 1, 9, 0))
          .version(0L)
          .build();
    }

    @Test
    @DisplayName("필터 없이 전체 이력을 페이지네이션해 반환한다")
    void returnsPagedRecordsWithNoFilter() {
      ConductRecord record = sampleRecord();
      given(conductRecordRepository.findByStudentWithFilters(
          studentUserId, null, null, null, PageRequest.of(0, 20)))
          .willReturn(new PageImpl<>(List.of(record), PageRequest.of(0, 20), 1));

      PageResponse<ConductStudentRecordResponse> result =
          conductService.getStudentRecords(studentUserId, null, null, null, 0, 20);

      assertThat(result.content()).hasSize(1);
      assertThat(result.content().get(0).id()).isEqualTo(501L);
      assertThat(result.totalElements()).isEqualTo(1);
      assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("type 필터를 적용하면 해당 종류의 기록만 반환한다")
    void returnsFilteredRecordsByType() {
      ConductRecord record = sampleRecord();
      given(conductRecordRepository.findByStudentWithFilters(
          studentUserId, ConductType.DEMERIT, null, null, PageRequest.of(0, 20)))
          .willReturn(new PageImpl<>(List.of(record), PageRequest.of(0, 20), 1));

      PageResponse<ConductStudentRecordResponse> result =
          conductService.getStudentRecords(
              studentUserId, ConductType.DEMERIT, null, null, 0, 20);

      assertThat(result.content()).hasSize(1);
      assertThat(result.content().get(0).type()).isEqualTo(ConductType.DEMERIT);
    }

    @Test
    @DisplayName("dateFrom, dateTo를 함께 제공하면 기간 필터를 적용한다")
    void returnsFilteredRecordsByDateRange() {
      LocalDate from = LocalDate.of(2026, 8, 1);
      LocalDate to = LocalDate.of(2026, 8, 31);
      ConductRecord record = sampleRecord();
      given(conductRecordRepository.findByStudentWithFilters(
          studentUserId, null, from, to, PageRequest.of(0, 20)))
          .willReturn(new PageImpl<>(List.of(record), PageRequest.of(0, 20), 1));

      PageResponse<ConductStudentRecordResponse> result =
          conductService.getStudentRecords(studentUserId, null, from, to, 0, 20);

      assertThat(result.content()).hasSize(1);
    }

    @Test
    @DisplayName("page가 음수이면 CONDUCT_007 예외를 던진다")
    void throwsWhenPageIsNegative() {
      assertThatThrownBy(() ->
          conductService.getStudentRecords(studentUserId, null, null, null, -1, 20))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.INVALID_PAGE);
    }

    @Test
    @DisplayName("size가 0이면 CONDUCT_007 예외를 던진다")
    void throwsWhenSizeIsZero() {
      assertThatThrownBy(() ->
          conductService.getStudentRecords(studentUserId, null, null, null, 0, 0))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.INVALID_PAGE);
    }

    @Test
    @DisplayName("size가 100 초과이면 CONDUCT_007 예외를 던진다")
    void throwsWhenSizeExceedsMax() {
      assertThatThrownBy(() ->
          conductService.getStudentRecords(studentUserId, null, null, null, 0, 101))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.INVALID_PAGE);
    }

    @Test
    @DisplayName("dateFrom만 제공하면 CONDUCT_008 예외를 던진다")
    void throwsWhenOnlyDateFromProvided() {
      assertThatThrownBy(() ->
          conductService.getStudentRecords(
              studentUserId, null, LocalDate.of(2026, 8, 1), null, 0, 20))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.INVALID_DATE_RANGE);
    }

    @Test
    @DisplayName("dateTo만 제공하면 CONDUCT_008 예외를 던진다")
    void throwsWhenOnlyDateToProvided() {
      assertThatThrownBy(() ->
          conductService.getStudentRecords(
              studentUserId, null, null, LocalDate.of(2026, 8, 31), 0, 20))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.INVALID_DATE_RANGE);
    }

    @Test
    @DisplayName("dateFrom이 dateTo보다 이후이면 CONDUCT_008 예외를 던진다")
    void throwsWhenDateFromAfterDateTo() {
      assertThatThrownBy(() ->
          conductService.getStudentRecords(
              studentUserId, null,
              LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1), 0, 20))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(ConductErrorCode.INVALID_DATE_RANGE);
    }
  }
}
