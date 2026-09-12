-- FCM 디바이스 토큰 테이블 생성 (#163)
CREATE TABLE device_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    fcm_token VARCHAR(255) NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT uk_device_token_user UNIQUE (user_id),
    CONSTRAINT fk_device_token_user
        FOREIGN KEY (user_id) REFERENCES user(id)
);
