package com.remake.gone.user.service;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.user.dto.MyProfileResponse;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.exception.UserErrorCode;
import com.remake.gone.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 본인의 회원 정보(별명, 프로필 사진 등)를 관리하는 서비스.
 */
@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;

  /**
   * 본인의 프로필 정보를 조회합니다.
   *
   * @param userId 조회할 사용자 ID (Access Token에서 추출됨)
   * @return 현재 닉네임과 프로필 사진 설정 여부
   */
  @Transactional(readOnly = true)
  public MyProfileResponse getMyProfile(Long userId) {
    User user = findAuthenticatedUser(userId);

    return new MyProfileResponse(user.getName(), user.getProfileImageKey() != null);
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
