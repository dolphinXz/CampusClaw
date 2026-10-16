-- 第 5 课修复：文件式技能不再要求 code（实体已按可空处理），存量行保留原值。
-- V4 遗漏了该列的可空性调整，导致教师上传技能文件时插入违约（Column 'code' cannot be null）。
ALTER TABLE ai_skills MODIFY code VARCHAR(64) NULL;
