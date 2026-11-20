-- 1.1.0 收官（P2c 裁定 zu5skyzg）：平台通知点击跳转需要定位目标对象
-- （反馈详情等），通知行增加通用引用 id；事项类通知仍走 item_id，本列为空。
ALTER TABLE notification ADD COLUMN ref_id BIGINT;
