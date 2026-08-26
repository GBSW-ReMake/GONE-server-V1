-- outing.departed_at/returned_at 초 단위 이하 정밀도 보강 (#97 코드 리뷰 반영)
-- outing_location.recorded_at과 동일하게 DATETIME(6)으로 맞춰, 도착 보고와 근접한 시각에
-- 기록된 위치 핑이 초 단위로 잘려 recordedAt 정렬 순서가 뒤바뀌는 것을 방지한다.
ALTER TABLE outing
    MODIFY COLUMN departed_at DATETIME(6) NULL,
    MODIFY COLUMN returned_at DATETIME(6) NULL;
