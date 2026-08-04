-- 역할(Role) 정의 및 시드 데이터
CREATE TABLE role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(30) UNIQUE NOT NULL,
    name VARCHAR(30) NOT NULL
);

INSERT INTO role (code, name) VALUES
    ('STUDENT', '학생'),
    ('TEACHER', '선생님'),
    ('ADMIN', '관리자'),
    ('STUDENT_COUNCIL', '학생회'),
    ('DISCIPLINE', '선도부'),
    ('GENERAL_AFFAIRS', '총무부'),
    ('ENVIRONMENT', '환경부'),
    ('BROADCASTING', '방송부'),
    ('ATHLETICS', '체육부');

-- 사용자-역할 다대다 매핑 (한 사용자가 여러 역할을 가질 수 있음)
CREATE TABLE user_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (user_id) REFERENCES user(id),
    FOREIGN KEY (role_id) REFERENCES role(id),
    UNIQUE KEY uq_user_role (user_id, role_id)
);
