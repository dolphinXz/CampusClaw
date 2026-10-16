# -*- coding: utf-8 -*-
"""GET /api/skills：文件式技能列表；解析字段与 rawText 与数据库一致；学生可读。"""
import common


def _seed_skills_parseable(ctx):
    token = common.login(common.TEACHER_A)
    status, data = common.request("GET", "/api/skills", token=token)
    common.assert_eq(status, 200, "列表状态码")
    common.assert_true(isinstance(data, list), "响应应为数组")

    guide = next((s for s in data if s.get("name") == "解题引导"), None)
    common.assert_true(guide is not None, "应存在解题引导技能")
    for field in ("id", "name", "description", "conditions", "keywords",
                  "subjectScope", "tool", "enabled", "content", "rawText"):
        common.assert_true(field in guide, "技能应含字段 " + field)

    # 解析字段与数据库一致
    row = common.db_one(
        "SELECT s.name, s.enabled, s.conditions, s.raw_text FROM ai_skills s "
        "JOIN class_rooms c ON c.id = s.class_id "
        "WHERE c.code = 'CLASS_A' AND s.name = '解题引导'")
    common.assert_true(row is not None, "数据库应有本班解题引导技能")
    common.assert_eq(guide["name"], row["name"], "名称与数据库一致")
    common.assert_eq(bool(guide["enabled"]), bool(row["enabled"]), "启用状态与数据库一致")
    common.assert_eq(guide["rawText"], row["raw_text"], "原文与数据库一致")
    # keywords 为适用条件按逗号/顿号/分号/竖线的切词结果（与数据库 conditions 一致）
    expected = [kw.strip() for kw in
                (row["conditions"].replace("，", ",").replace("、", ",")
                 .replace("；", ",").replace("|", ",").split(","))
                if kw.strip()]
    common.assert_eq(guide["keywords"], expected, "切词结果与适用条件一致")

    # 已停用技能也在教师列表里（启停由教师管理）
    common.assert_true(any(s["name"] == "卖萌回答" and not s["enabled"] for s in data),
                       "教师应看到已停用技能")


def _student_can_read(ctx):
    token = common.login(common.STUDENT_A)
    status, data = common.request("GET", "/api/skills", token=token)
    common.assert_eq(status, 200, "学生可读技能列表")
    common.assert_true(any(s.get("name") == "解题引导" for s in data), "学生可见解题引导")


def _unauthorized_401(ctx):
    status, _ = common.request("GET", "/api/skills")
    common.assert_eq(status, 401, "未授权应 401")


CASES = [
    ("种子技能解析字段与数据库一致", _seed_skills_parseable),
    ("学生可读技能列表", _student_can_read),
    ("未授权读取返回 401", _unauthorized_401),
]
