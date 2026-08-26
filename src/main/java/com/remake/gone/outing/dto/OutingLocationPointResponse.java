package com.remake.gone.outing.dto;

import java.time.LocalDateTime;

/**
 * 동선을 이루는 좌표 하나(#97). 출발/도착 좌표(#43)와 위치 핑(#97)이 같은 형태로 합성된다.
 *
 * @param latitude   위도
 * @param longitude  경도
 * @param recordedAt 이 좌표가 기록된 시각
 */
public record OutingLocationPointResponse(
    Double latitude,
    Double longitude,
    LocalDateTime recordedAt
) {}
