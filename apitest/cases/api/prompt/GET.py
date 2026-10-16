# -*- coding: utf-8 -*-
"""GET /api/prompt：任意已登录用户可读；内容与数据库一致（或为内置默认）。"""
import common


def _teacher_reads_prompt(ctx):
    token = common.login(common.TEACHER_A)
    status, data = common.request("GET", "/api/prompt", token=token)
    common.assert_eq(status, 200, "读取状态码")
    common.assert_true(isinstance(data.get("content"), str) and data["content"], "应含提示词内容")

    row = common.db_one(
        "SELECT p.content FROM system_prompts p JOIN class_rooms c ON c.id = p.class_id "
        "WHERE c.code = 'CLASS_A'")
    if row is None:
        common.assert_true(data.get("isDefault") is True, "无记录时应标记默认")
    else:
        common.assert_eq(data.get("content"), row["content"], "响应与数据库一致")
        common.assert_true(data.get("isDefault") is False, "有记录时非默认")


def _student_can_read(ctx):
    token = common.login(common.STUDENT_A)
    status, _ = common.request("GET", "/api/prompt", token=token)
    common.assert_eq(status, 200, "学生可读提示词")


def _unauthorized_401(ctx):
    status, _ = common.request("GET", "/api/prompt")
    common.assert_eq(status, 401, "无 token 应 401")


CASES = [
    ("教师读取提示词且与数据库一致", _teacher_reads_prompt),
    ("学生可读提示词", _student_can_read),
    ("未授权读取返回 401", _unauthorized_401),
]
