# -*- coding: utf-8 -*-
"""PUT /api/prompt：教师保存与数据库一致；学生 403 且不产生写入。"""
import common


def _teacher_save_persists(ctx):
    token = common.login(common.TEACHER_A)
    content = "你是三年级的解题助手（apitest 保存）。"
    status, data = common.request("PUT", "/api/prompt", token=token,
                                  json_body={"content": content})
    common.assert_eq(status, 200, "保存状态码")
    common.assert_eq(data.get("content"), content, "响应回显保存内容")

    row = common.db_one(
        "SELECT p.content, p.updated_by FROM system_prompts p "
        "JOIN class_rooms c ON c.id = p.class_id WHERE c.code = 'CLASS_A'")
    common.assert_true(row is not None, "数据库应有本班提示词")
    common.assert_eq(row["content"], content, "数据库内容与响应一致")

    # 读取接口返回新值（下一轮对话生效的前提）
    status, data = common.request("GET", "/api/prompt", token=token)
    common.assert_eq(data.get("content"), content, "重新读取为新内容")


def _student_save_403_no_write(ctx):
    before = common.db_one(
        "SELECT p.content FROM system_prompts p "
        "JOIN class_rooms c ON c.id = p.class_id WHERE c.code = 'CLASS_A'")
    token = common.login(common.STUDENT_A)
    status, _ = common.request("PUT", "/api/prompt", token=token,
                               json_body={"content": "学生试图改提示词"})
    common.assert_eq(status, 403, "学生保存应 403")

    after = common.db_one(
        "SELECT p.content FROM system_prompts p "
        "JOIN class_rooms c ON c.id = p.class_id WHERE c.code = 'CLASS_A'")
    common.assert_eq(after, before, "失败请求不得改变数据库")


def _blank_content_400(ctx):
    token = common.login(common.TEACHER_A)
    status, _ = common.request("PUT", "/api/prompt", token=token,
                               json_body={"content": "   "})
    common.assert_eq(status, 400, "空提示词应 400")


CASES = [
    ("教师保存提示词且数据库一致", _teacher_save_persists),
    ("学生保存被拒绝且无写入", _student_save_403_no_write),
    ("空内容返回 400", _blank_content_400),
]
