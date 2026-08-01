-- 학교가 엑셀로 미리 삽입하는 원본 명단 (학생+선생님 통합)
CREATE TABLE gbsw (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      type ENUM('STUDENT', 'TEACHER') NOT NULL,
                      name VARCHAR(50) NOT NULL,
                      phone_number VARCHAR(20) UNIQUE NOT NULL,
                      birth_date DATE,
    -- 학생 전용 (선생님이면 NULL)
                      grade INT NULL,
                      class_no INT NULL,
                      number INT NULL,
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

                      UNIQUE KEY uq_class_slot (grade, class_no, number)
);

-- 실사용자가 가입하면서 만드는 계정
CREATE TABLE user (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      gbsw_id BIGINT NOT NULL,
                      login_id VARCHAR(50) UNIQUE NOT NULL,
                      password_hash VARCHAR(255) NOT NULL,
                      phone_number VARCHAR(20) UNIQUE NOT NULL,
                      profile_image_key VARCHAR(500),
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      deleted_at DATETIME NULL,

                      FOREIGN KEY (gbsw_id) REFERENCES gbsw(id),
                      UNIQUE KEY uq_gbsw (gbsw_id)
);