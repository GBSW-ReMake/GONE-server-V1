package com.remake.gone.user.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.file.service.R2FileService;
import com.remake.gone.gbsw.entity.Gbsw;
import com.remake.gone.gbsw.enums.GbswType;
import com.remake.gone.user.dto.MyProfileResponse;
import com.remake.gone.user.dto.UserSearchResponse;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.exception.UserErrorCode;
import com.remake.gone.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 본인의 회원 정보(별명, 프로필 사진 등)를 관리하고, 가입된 사용자를 실명으로
 * 검색하는 서비스.
 */
@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final R2FileService r2FileService;

  /**
   * 본인의 프로필 정보를 조회합니다.
   *
   * @param userId 조회할 사용자 ID (Access Token에서 추출됨)
   * @return 현재 닉네임, 프로필 사진 설정 여부/URL, 실명/학년/반
   */
  @Transactional(readOnly = true)
  public MyProfileResponse getMyProfile(Long userId) {
    User user = findAuthenticatedUser(userId);

    boolean hasProfileImage = user.getProfileImageKey() != null;
    String profileImageUrl = hasProfileImage
        ? r2FileService.generateDownloadUrl(user.getProfileImageKey())
        : null;
    Gbsw gbsw = user.getGbsw();
    return new MyProfileResponse(
        user.getName(), hasProfileImage, profileImageUrl,
        gbsw.getName(), gbsw.getGrade(), gbsw.getClassNo());
  }

  /**
   * 실명에 검색어가 부분 일치하는 가입된 사용자를 검색합니다.
   *
   * @param query 검색어(실명 부분 일치)
   * @return 검색 결과 목록. 학생이면 학년/반을 포함하고, 선생님이면 {@code null}
   */
  @Transactional(readOnly = true)
  public List<UserSearchResponse> search(String query) {
    return userRepository.searchByRealNameContaining(query).stream()
        .map(this::toSearchResponse)
        .toList();
  }

  private UserSearchResponse toSearchResponse(User user) {
    Gbsw gbsw = user.getGbsw();
    boolean isStudent = gbsw.getType() == GbswType.STUDENT;
    return new UserSearchResponse(
        user.getId(),
        user.getName(),
        gbsw.getName(),
        isStudent ? gbsw.getGrade() : null,
        isStudent ? gbsw.getClassNo() : null);
  }

  /**
   * 본인의 별명을 변경합니다.
   *
   * <p>지금 쓰고 있는 별명으로 다시 제출하는 경우(변경 없음)는 중복으로 취급하지 않는다.
   *
   * @param userId  변경할 사용자 ID (Access Token에서 추출됨)
   * @param newName 새로 설정할 별명
   */
  @Transactional
  public void changeName(Long userId, String newName) {
    User user = findAuthenticatedUser(userId);

    if (!newName.equals(user.getName()) && userRepository.existsByName(newName)) {
      throw new CustomException(UserErrorCode.NAME_ALREADY_EXISTS);
    }

    user.setName(newName);
    userRepository.save(user);
  }

  /**
   * 본인의 프로필 사진 key를 변경합니다. R2에 실제로 업로드됐는지는 호출하는 쪽
   * ({@code FileController})에서 이미 확인했다고 가정한다.
   *
   * @param userId 변경할 사용자 ID (Access Token에서 추출됨)
   * @param key    R2에 업로드된 이미지 객체의 key
   */
  @Transactional
  public void updateProfileImage(Long userId, String key) {
    User user = findAuthenticatedUser(userId);

    user.setProfileImageKey(key);
    userRepository.save(user);
  }

  /**
   * Access Token의 userId로 사용자를 조회한다. 토큰은 유효했지만(서명/만료 통과) 그 시점 이후
   * 계정이 삭제되는 등의 이유로 더는 존재하지 않는 상황이므로, 서버 오류(500)가 아니라
   * "인증이 더 이상 유효하지 않다"는 의미로 {@link CommonErrorCode#UNAUTHORIZED}(401)를 던진다.
   */
  private User findAuthenticatedUser(Long userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new CustomException(CommonErrorCode.UNAUTHORIZED));
  }
}
