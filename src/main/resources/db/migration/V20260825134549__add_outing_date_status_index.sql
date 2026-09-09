-- 학생/선생님으로 좁혀지지 않는 하루 전체 조회(#98)를 위한 인덱스.
-- 기존 idx_outing_student_date는 student_user_id가 선두 컬럼이라 이 쿼리에 못 쓴다.
ALTER TABLE outing
    ADD INDEX idx_outing_date_status (outing_date, status);
