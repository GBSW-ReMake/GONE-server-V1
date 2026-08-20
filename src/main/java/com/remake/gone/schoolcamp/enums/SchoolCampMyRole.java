package com.remake.gone.schoolcamp.enums;

/**
 * 본인 참여 내역 조회(#69)에서, 조회를 요청한 본인이 그 신청에서 맡은 역할.
 *
 * <p>{@code TEACHER}는 담당 선생님으로 지정된 계정 본인이 조회할 때 쓴다(가입된
 * {@code teacherUser}만 대상 — 자유 입력 {@code teacherName}은 로그인 계정이 없어 해당
 * 없음).
 */
public enum SchoolCampMyRole {
  APPLICANT,
  MEMBER,
  TEACHER
}
