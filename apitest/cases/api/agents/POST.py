# -*- coding: utf-8 -*-
"""POST /api/agents：教师创建；班内重名 400；学生 403；名称超长 400。"""
import time

import common


def _unique_name(prefix):
    # 智能体没有删除接口：名称加时间戳避免跨轮次重名，用例收尾停用即可
    return "%s-%d" % (prefix, int(time.time() * 1000) % 100000000)


def _teacher_creates_and_db_matches(ctx):
    token = common.login(common.TEACHER_A)
    name = _unique_name("apitest 助手")
    status, data = common.request("POST", "/api/agents", token=token,
                                  json_body={"name": name, "rolePrompt": "你是测试助手"})
    common.assert_eq(status, 200, "创建状态码")
    common.assert_eq(data["name"], name, "名称回显")
    common.assert_eq(data["rolePrompt"], "你是测试助手", "角色提示词回显")
    common.assert_true(data["enabled"], "新建默认启用")

    row = common.db_one(
        "SELECT a.name, a.role_prompt, a.enabled, c.code FROM assistants a "
        "JOIN class_rooms c ON c.id = a.class_id WHERE a.id = %s", (data["id"],))
    common.assert_true(row is not None, "数据库应有记录")
    common.assert_eq(row["name"], name, "名称落库")
    common.assert_eq(row["role_prompt"], "你是测试助手", "提示词落库")
    common.assert_eq(row["code"], "CLASS_A", "班级为创建者班级")

    common.request("PUT", "/api/agents/%d" % data["id"], token=token, json_body={"enabled": False})


def _empty_role_prompt_is_null(ctx):
    token = common.login(common.TEACHER_A)
    status, data = common.request("POST", "/api/agents", token=token,
                                  json_body={"name": _unique_name("默认角色助手"), "rolePrompt": ""})
    common.assert_eq(status, 200, "空角色提示词不拒绝")
    common.assert_true(not data["rolePrompt"], "空提示词存空值（运行时回退默认角色）")
    common.request("PUT", "/api/agents/%d" % data["id"], token=token, json_body={"enabled": False})


def _duplicate_name_400(ctx):
    token = common.login(common.TEACHER_A)
    _, agents = common.request("GET", "/api/agents", token=token)
    existing = agents[0]["name"]
    status, data = common.request("POST", "/api/agents", token=token,
                                  json_body={"name": existing})
    common.assert_eq(status, 400, "班内重名应 400")
    common.assert_true("同名" in data.get("message", ""), "错误应说明重名：%s" % data)


def _student_create_403(ctx):
    student = common.login(common.STUDENT_A)
    status, _ = common.request("POST", "/api/agents", token=student,
                               json_body={"name": "学生想建的助手"})
    common.assert_eq(status, 403, "学生创建应 403")


def _name_too_long_400(ctx):
    token = common.login(common.TEACHER_A)
    status, _ = common.request("POST", "/api/agents", token=token,
                               json_body={"name": "超长名称" * 20})
    common.assert_eq(status, 400, "名称超长应 400")


CASES = [
    ("教师创建且数据库一致", _teacher_creates_and_db_matches),
    ("空角色提示词回退默认", _empty_role_prompt_is_null),
    ("班内重名返回 400", _duplicate_name_400),
    ("学生创建返回 403", _student_create_403),
    ("名称超长返回 400", _name_too_long_400),
]
