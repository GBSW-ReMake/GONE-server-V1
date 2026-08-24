package com.remake.gone.outing.utils;

/**
 * 위도/경도 좌표 간 거리 계산 순수 함수(#43).
 */
public final class GeoUtils {

  private static final double EARTH_RADIUS_METERS = 6_371_000;

  private GeoUtils() {
  }

  /**
   * 하버사인 공식으로 두 좌표 사이의 거리를 계산한다.
   *
   * @param lat1 첫 번째 지점의 위도
   * @param lon1 첫 번째 지점의 경도
   * @param lat2 두 번째 지점의 위도
   * @param lon2 두 번째 지점의 경도
   * @return 두 지점 사이의 거리(미터)
   */
  public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    double lat1Rad = Math.toRadians(lat1);
    double lat2Rad = Math.toRadians(lat2);
    double deltaLat = Math.toRadians(lat2 - lat1);
    double deltaLon = Math.toRadians(lon2 - lon1);

    double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
        + Math.cos(lat1Rad) * Math.cos(lat2Rad)
        * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

    return EARTH_RADIUS_METERS * c;
  }
}
