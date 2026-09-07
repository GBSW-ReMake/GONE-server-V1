-- 전체 읽음 처리와 안 읽은 알림 개수 조회의 user_id/is_read 조건을 지원한다 (#128).
ALTER TABLE notification
    ADD INDEX idx_notification_user_read (user_id, is_read);
