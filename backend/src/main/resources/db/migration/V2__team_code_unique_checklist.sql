-- M1 收尾（2026-11-17 验收拍板）：
-- 1) 小组代号全平台唯一、不区分大小写（设计 §5.1 修订，验收意见三-4）。
--    存量代号已统一大写存储，lower(code) 唯一索引同时兜住历史与未来数据。
CREATE UNIQUE INDEX uk_team_code_lower ON team (lower(code));

-- 2) 检查清单表 checklist_entry 已在 V1 建好（设计 §5.2），本轮无结构变更，
--    仅补按事项+排序的查询索引，支撑事项详情返回清单。
CREATE INDEX idx_checklist_item_sort ON checklist_entry(item_id, sort_order);
