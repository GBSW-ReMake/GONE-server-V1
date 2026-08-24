package com.remake.gone.outing.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GeoUtils}에 대한 단위 테스트.
 */
class GeoUtilsTest {

  @Test
  @DisplayName("같은 지점 사이의 거리는 0이다")
  void returnsZeroForSamePoint() {
    double distance = GeoUtils.distanceMeters(36.1234, 128.4321, 36.1234, 128.4321);

    assertThat(distance).isCloseTo(0, within(0.001));
  }

  @Test
  @DisplayName("위도 1도 차이는 약 111.2km(오차 허용 범위 내)다")
  void calculatesKnownDistanceBetweenTwoPoints() {
    // 경도가 같을 때 위도 1도 차이는 지구 반지름 기준으로 약 111.2km에 해당한다.
    double distance = GeoUtils.distanceMeters(36.0, 128.0, 37.0, 128.0);

    assertThat(distance).isCloseTo(111_195, within(500.0));
  }

  @Test
  @DisplayName("반경 경계값 정확히 위에 있는 지점의 거리는 그 반경과 같다")
  void calculatesDistanceAtRadiusBoundary() {
    // 학교(0,0)에서 정확히 정북 방향으로 200m 떨어진 지점.
    double metersPerDegreeLatitude = 111_195;
    double latitudeOffset = 200 / metersPerDegreeLatitude;

    double distance = GeoUtils.distanceMeters(0, 0, latitudeOffset, 0);

    assertThat(distance).isCloseTo(200, within(1.0));
  }
}
