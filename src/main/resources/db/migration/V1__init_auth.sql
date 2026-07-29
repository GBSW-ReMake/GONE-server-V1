CREATE TABLE student (
                         id BIGINT PRIMARY KEY AUTO_INCREMENT,
                         grade INT NOT NULL,
                         class_no INT NOT NULL,
                         number INT NOT NULL,
                         name VARCHAR(50) NOT NULL,
                         birth_date DATE NOT NULL,
                         phone_number VARCHAR(20) NOT NULL,
                         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

                         UNIQUE KEY uq_class_slot (grade, class_no, number),
                         UNIQUE KEY uq_phone (phone_number)
);

CREATE TABLE user (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      phone_number VARCHAR(20) UNIQUE NOT NULL,
                      login_id VARCHAR(50) UNIQUE NOT NULL,
                      password_hash VARCHAR(255) NOT NULL,
                      profile_image_url VARCHAR(500),
                      name VARCHAR(50) NOT NULL,
                      student_id BIGINT NULL,
                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      deleted_at DATETIME NULL,

                      FOREIGN KEY (student_id) REFERENCES student(id),
                      UNIQUE KEY uq_student (student_id)
);