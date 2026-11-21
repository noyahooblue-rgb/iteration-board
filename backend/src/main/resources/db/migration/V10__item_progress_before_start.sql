-- 1.1.0-rc2（裁定 hsa43amn 二.2）：撤销开始（UNSTART）的语义改为「进度回到开始前的值」
-- 而非清零。事项新增 progress_before_start：START 流转时把当时的 progress_percent
-- 快照存入本列，UNSTART 时据此恢复。历史行为空，UNSTART 回落为 0（与旧行为一致）。
ALTER TABLE item ADD COLUMN progress_before_start INTEGER;
