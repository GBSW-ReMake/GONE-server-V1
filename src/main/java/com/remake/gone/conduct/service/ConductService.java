package com.remake.gone.conduct.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.conduct.dto.ConductCategoryResponse;
import com.remake.gone.conduct.dto.ConductGrantRequest;
import com.remake.gone.conduct.dto.ConductRecordResponse;
import com.remake.gone.conduct.entity.ConductCategory;
import com.remake.gone.conduct.entity.ConductRecord;
import com.remake.gone.conduct.exception.ConductErrorCode;
import com.remake.gone.conduct.repository.ConductCategoryRepository;
import com.remake.gone.conduct.repository.ConductRecordRepository;
import com.remake.gone.role.repository.UserRoleRepository;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 상/벌점(Conduct) 도메인 서비스. */
@Service
@RequiredArgsConstructor
public class ConductService {

  private static final String STUDENT_ROLE_CODE = "STUDENT";

  private final ConductCategoryRepository conductCategoryRepository;
  private final ConductRecordRepository conductRecordRepository;
  private final UserRepository userRepository;
  private final UserRoleRepository userRoleRepository;

  /**
   * 활성 카테고리 목록을 조회합니다.
   *
   * @return {@code active = true}인 카테고리를 {@code id} 오름차순으로 정렬한 목록
   */
  @Transactional(readOnly = true)
  public List<ConductCategoryResponse> getCategories() {
    return conductCategoryRepository.findByActiveTrueOrderByIdAsc().stream()
        .map(ConductCategoryResponse::from)
        .toList();
  }

  /**
   * 교사가 학생에게 상/벌점을 부여합니다.
   *
   * @param teacherUserId 부여하는 교사 사용자 ID (Access Token에서 추출됨)
   * @param request       부여 요청 정보
   * @return 생성된 상/벌점 기록
   */
  @Transactional
  public ConductRecordResponse grantConduct(Long teacherUserId, ConductGrantRequest request) {
    ConductCategory category = conductCategoryRepository.findById(request.categoryId())
        .filter(ConductCategory::isActive)
        .orElseThrow(() -> new CustomException(ConductErrorCode.CATEGORY_NOT_FOUND_OR_INACTIVE));

    User student = userRepository.findById(request.studentUserId())
        .orElseThrow(() -> new CustomException(ConductErrorCode.STUDENT_NOT_FOUND));

    List<String> roles = userRoleRepository.findRoleCodesByUserId(request.studentUserId());
    if (!roles.contains(STUDENT_ROLE_CODE)) {
      throw new CustomException(ConductErrorCode.NOT_STUDENT_ROLE);
    }

    User teacher = userRepository.findById(teacherUserId)
        .orElseThrow(() -> new CustomException(CommonErrorCode.NOT_FOUND));

    ConductRecord record = ConductRecord.builder()
        .student(student)
        .teacher(teacher)
        .category(category)
        .type(category.getType())
        .points(category.getPoints())
        .detail(request.detail())
        .build();

    return ConductRecordResponse.from(conductRecordRepository.save(record));
  }
}
