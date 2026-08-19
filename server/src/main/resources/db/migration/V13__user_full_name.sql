-- 用户表增加姓名：First Name + Last Name，用于显示用户全名
ALTER TABLE sys_user
    ADD COLUMN first_name VARCHAR(50) DEFAULT NULL COMMENT '名（First Name）' AFTER nickname,
    ADD COLUMN last_name  VARCHAR(50) DEFAULT NULL COMMENT '姓（Last Name）' AFTER first_name;
