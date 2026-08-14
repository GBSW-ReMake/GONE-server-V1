-- 알림(Notification) 테이블 생성 (#59 Notification 엔티티 + 공통 발송 모듈)
CREATE TABLE notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    body VARCHAR(500) NOT NULL,
    type VARCHAR(50) NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (user_id) REFERENCES user(id),
    KEY idx_notification_user_created (user_id, created_at)
);
