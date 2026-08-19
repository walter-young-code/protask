-- 用户表增加密码版本号：修改/重置密码时 +1，用于让已签发的旧 token 立即失效
ALTER TABLE sys_user
    ADD COLUMN password_version INT NOT NULL DEFAULT 0 COMMENT '密码版本号: 修改/重置密码时 +1, 使旧 token 失效' AFTER status;
