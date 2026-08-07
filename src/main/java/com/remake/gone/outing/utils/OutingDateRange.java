package com.remake.gone.outing.utils;

import java.time.LocalDate;

/**
 * 조회 시작일~종료일 범위(#41). 양 끝 날짜를 포함한다({@code [from, to]}).
 *
 * @param from 시작일
 * @param to   종료일
 */
public record OutingDateRange(LocalDate from, LocalDate to) {}
