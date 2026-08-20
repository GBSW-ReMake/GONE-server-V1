-- 같은 신청에 같은 학생이 팀원으로 중복 등록되는 것을 DB 레벨에서 방지 (#70 코드 리뷰).
-- student_user_id가 NULL인 "기타"(게스트) 행끼리는 MySQL 유니크 인덱스 규칙상 서로 충돌하지
-- 않는다 — 이 제약은 "같은 학생 중복"만 막고, 게스트 중복 방지 목적이 아니다.
ALTER TABLE school_camp_member
    ADD CONSTRAINT uq_member_application_student UNIQUE (application_id, student_user_id);
