package com.remake.gone.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

  @Mock
  private R2FileService r2FileService;

  @InjectMocks
  private UserService userService;

  private static final Long USER_ID = 1L;

  private User existingUser() {
    return User.builder().id(USER_ID).name("기존별명").build();
  }

  private Gbsw studentGbsw(String name) {
    return Gbsw.builder().type(GbswType.STUDENT).name(name).grade(3).classNo(1).number(18)
        .build();
  }

  private Gbsw teacherGbsw(String name) {
    return Gbsw.builder().type(GbswType.TEACHER).name(name).build();
  }

  @Nested
  @DisplayName("getMyProfile")
  class GetMyProfile {

    @Test
    @DisplayName("프로필 사진이 있으면 hasProfileImage와 URL을 함께 반환한다")
    void returnsHasProfileImageTrue() {
      User user = User.builder().id(USER_ID).name("3118정문경").profileImageKey("profile/1/a.jpg")
          .gbsw(studentGbsw("김정문")).build();
      given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
      given(r2FileService.generateDownloadUrl("profile/1/a.jpg"))
          .willReturn("https://example.com/a.jpg");

      MyProfileResponse response = userService.getMyProfile(USER_ID);

      assertThat(response).isEqualTo(new MyProfileResponse(
          "3118정문경", true, "https://example.com/a.jpg", "김정문", 3, 1));
    }

    @Test
    @DisplayName("프로필 사진이 없으면 hasProfileImage를 false로, URL은 null로 반환한다")
    void returnsHasProfileImageFalse() {
      User user = User.builder().id(USER_ID).name("3118정문경").gbsw(studentGbsw("김정문")).build();
      given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

      MyProfileResponse response = userService.getMyProfile(USER_ID);

      assertThat(response).isEqualTo(new MyProfileResponse(
          "3118정문경", false, null, "김정문", 3, 1));
    }

    @Test
    @DisplayName("선생님 계정이면 학년/반은 null로 반환한다")
    void returnsNullGradeAndClassNoForTeacher() {
      User user = User.builder().id(USER_ID).name("쌤").gbsw(teacherGbsw("김선생")).build();
      given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

      MyProfileResponse response = userService.getMyProfile(USER_ID);

      assertThat(response).isEqualTo(new MyProfileResponse(
          "쌤", false, null, "김선생", null, null));
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
  @DisplayName("search")
  class Search {

    @Test
    @DisplayName("학생 결과는 학년/반을 포함해서 반환한다")
    void includesGradeAndClassNoForStudent() {
      User student = User.builder().id(55L).name("영희").gbsw(studentGbsw("이영희")).build();
      given(userRepository.searchByRealNameContaining("영희")).willReturn(List.of(student));

      List<UserSearchResponse> results = userService.search("영희");

      assertThat(results).containsExactly(new UserSearchResponse(55L, "영희", "이영희", 3, 1));
    }

    @Test
    @DisplayName("선생님 결과는 학년/반을 null로 반환한다")
    void excludesGradeAndClassNoForTeacher() {
      User teacher = User.builder().id(61L).name("쌤").gbsw(teacherGbsw("이영수")).build();
      given(userRepository.searchByRealNameContaining("영수")).willReturn(List.of(teacher));

      List<UserSearchResponse> results = userService.search("영수");

      assertThat(results).containsExactly(new UserSearchResponse(61L, "쌤", "이영수", null, null));
    }

    @Test
    @DisplayName("일치하는 결과가 없으면 빈 목록을 반환한다")
    void returnsEmptyListWhenNoMatch() {
      given(userRepository.searchByRealNameContaining("없는이름")).willReturn(List.of());

      List<UserSearchResponse> results = userService.search("없는이름");

      assertThat(results).isEmpty();
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
