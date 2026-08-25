package com.remake.gone.outing.dto;

import com.remake.gone.outing.enums.OutingStatus;
import java.util.List;

/**
 * 외출증 위치/동선 조회 응답 DTO(#97).
 *
 * @param code   외부 식별자 코드(내부 PK가 아니라 프론트에 표시할 코드)
 * @param status 외출증 상태(진행 중이면 {@code path}가 계속 늘어날 수 있음을 의미)
 * @param path   출발 좌표(있으면) → 위치 핑(시간순) → 도착 좌표(있으면) 순으로 합성된 동선,
 *               전부 {@code recordedAt} 오름차순
 */
public record OutingLocationsResponse(
    String code,
    OutingStatus status,
    List<OutingLocationPointResponse> path
) {}
