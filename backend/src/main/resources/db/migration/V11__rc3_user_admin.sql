-- 修订六 rc3（契约 2）：用户列表显示最近登录时间
ALTER TABLE app_user ADD COLUMN last_login_at timestamptz;
