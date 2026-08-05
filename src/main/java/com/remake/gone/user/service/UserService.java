package com.remake.gone.user.service;

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
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new IllegalStateException("인증된 사용자를 찾을 수 없습니다: " + userId));

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
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new IllegalStateException("인증된 사용자를 찾을 수 없습니다: " + userId));

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
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new IllegalStateException("인증된 사용자를 찾을 수 없습니다: " + userId));

    user.setProfileImageKey(key);
    userRepository.save(user);
  }
}
