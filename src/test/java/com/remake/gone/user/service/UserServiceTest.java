package com.remake.gone.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.remake.gone.common.exception.CommonErrorCode;
import com.remake.gone.common.exception.CustomException;
import com.remake.gone.user.dto.MyProfileResponse;
import com.remake.gone.user.entity.User;
import com.remake.gone.user.exception.UserErrorCode;
import com.remake.gone.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link UserService}에 대한 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock
  private UserRepository userRepository;

  @InjectMocks
  private UserService userService;

  private static final Long USER_ID = 1L;

  private User existingUser() {
    return User.builder().id(USER_ID).name("기존별명").build();
  }

  @Nested
  @DisplayName("getMyProfile")
  class GetMyProfile {

    @Test
    @DisplayName("프로필 사진이 있으면 hasProfileImage를 true로 반환한다")
    void returnsHasProfileImageTrue() {
      User user = User.builder().id(USER_ID).name("3118정문경").profileImageKey("profile/1/a.jpg")
          .build();
      given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

      MyProfileResponse response = userService.getMyProfile(USER_ID);

      assertThat(response).isEqualTo(new MyProfileResponse("3118정문경", true));
    }

    @Test
    @DisplayName("프로필 사진이 없으면 hasProfileImage를 false로 반환한다")
    void returnsHasProfileImageFalse() {
      User user = User.builder().id(USER_ID).name("3118정문경").build();
      given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

      MyProfileResponse response = userService.getMyProfile(USER_ID);

      assertThat(response).isEqualTo(new MyProfileResponse("3118정문경", false));
    }

    @Test
    @DisplayName("존재하지 않는 사용자면 인증 만료로 취급해 401을 던진다")
    void throwsWhenUserNotFound() {
      given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

      assertThatThrownBy(() -> userService.getMyProfile(USER_ID))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(CommonErrorCode.UNAUTHORIZED);
    }
  }

  @Nested
  @DisplayName("changeName")
  class ChangeName {

    @Test
    @DisplayName("다른 사람이 쓰지 않는 이름이면 변경한다")
    void changesNameWhenAvailable() {
      User user = existingUser();
      given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
      given(userRepository.existsByName("새이름")).willReturn(false);

      userService.changeName(USER_ID, "새이름");

      assertThat(user.getName()).isEqualTo("새이름");
      verify(userRepository).save(user);
    }

    @Test
    @DisplayName("지금 쓰고 있는 이름을 그대로 다시 제출하면 중복 확인 없이 성공한다")
    void allowsResubmittingCurrentName() {
      User user = existingUser();
      given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

      userService.changeName(USER_ID, "기존별명");

      verify(userRepository, never()).existsByName(any());
      verify(userRepository).save(user);
    }

    @Test
    @DisplayName("다른 사람이 이미 쓰는 이름이면 거부한다")
    void throwsWhenNameTakenByOther() {
      User user = existingUser();
      given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
      given(userRepository.existsByName("남의별명")).willReturn(true);

      assertThatThrownBy(() -> userService.changeName(USER_ID, "남의별명"))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(UserErrorCode.NAME_ALREADY_EXISTS);

      verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 사용자면 인증 만료로 취급해 401을 던진다")
    void throwsWhenUserNotFound() {
      given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

      assertThatThrownBy(() -> userService.changeName(USER_ID, "새이름"))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(CommonErrorCode.UNAUTHORIZED);
    }
  }

  @Nested
  @DisplayName("updateProfileImage")
  class UpdateProfileImage {

    @Test
    @DisplayName("프로필 사진 key를 저장한다")
    void savesProfileImageKey() {
      User user = existingUser();
      given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

      userService.updateProfileImage(USER_ID, "profile/1/new.jpg");

      assertThat(user.getProfileImageKey()).isEqualTo("profile/1/new.jpg");
      verify(userRepository).save(user);
    }

    @Test
    @DisplayName("존재하지 않는 사용자면 인증 만료로 취급해 401을 던진다")
    void throwsWhenUserNotFound() {
      given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

      assertThatThrownBy(() -> userService.updateProfileImage(USER_ID, "profile/1/new.jpg"))
          .isInstanceOf(CustomException.class)
          .extracting(e -> ((CustomException) e).getErrorCode())
          .isEqualTo(CommonErrorCode.UNAUTHORIZED);
    }
  }
}
