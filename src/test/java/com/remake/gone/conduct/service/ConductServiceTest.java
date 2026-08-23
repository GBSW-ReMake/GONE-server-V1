package com.remake.gone.conduct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.remake.gone.common.exception.CustomException;
import com.remake.gone.conduct.dto.ConductCategoryResponse;
import com.remake.gone.conduct.dto.ConductGrantRequest;
import com.remake.gone.conduct.dto.ConductRecordResponse;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
      ConductGrantRequest request = new ConductGrantRequest(studentUserId, categoryId, "3교시 10분 지각");

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
}
