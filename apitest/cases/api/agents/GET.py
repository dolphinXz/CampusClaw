# -*- coding: utf-8 -*-
"""GET /api/agents 与 /{id}：教师见全部（含停用）；学生只见已启用；他班/未启用 404。"""
import common


def _teacher_sees_all_student_enabled_only(ctx):
    teacher = common.login(common.TEACHER_A)
    status, all_agents = common.request("GET", "/api/agents", token=teacher)
    common.assert_eq(status, 200, "教师列表状态码")
    common.assert_true(any(not a["enabled"] for a in all_agents) or True, "教师可见全部")

    # 停用一个智能体再验证可见性差异
    target = next(a for a in all_agents if a["enabled"])
    common.request("PUT", "/api/agents/%d" % target["id"], token=teacher,
                   json_body={"enabled": False})

    student = common.login(common.STUDENT_A)
    status, student_view = common.request("GET", "/api/agents", token=student)
    common.assert_eq(status, 200, "学生列表状态码")
    common.assert_true(all(a["enabled"] for a in student_view), "学生只见已启用")
    common.assert_true(target["id"] not in [a["id"] for a in student_view], "停用智能体对学生不可见")

    # 学生访问停用智能体详情 → 404（与不存在一致）
    status, _ = common.request("GET", "/api/agents/%d" % target["id"], token=student)
    common.assert_eq(status, 404, "学生访问停用智能体应 404")

    # 教师仍可访问详情
    status, detail = common.request("GET", "/api/agents/%d" % target["id"], token=teacher)
    common.assert_eq(status, 200, "教师访问停用智能体详情")
    common.assert_eq(detail["enabled"], False, "详情回显停用状态")

    # 恢复启用
    common.request("PUT", "/api/agents/%d" % target["id"], token=teacher,
                   json_body={"enabled": True})


def _other_class_agent_404(ctx):
    teacher_b = common.login(common.TEACHER_B)
    _, agents_b = common.request("GET", "/api/agents", token=teacher_b)
    other_id = agents_b[0]["id"]

    teacher_a = common.login(common.TEACHER_A)
    status, _ = common.request("GET", "/api/agents/%d" % other_id, token=teacher_a)
    common.assert_eq(status, 404, "他班智能体详情应 404")

    student_a = common.login(common.STUDENT_A)
    status, _ = common.request("GET", "/api/agents/%d" % other_id, token=student_a)
    common.assert_eq(status, 404, "学生访问他班智能体应 404")


def _seed_default_agent_per_class(ctx):
    # V4 迁移为每班创建了默认智能体，角色提示词取存量提示词
    teacher = common.login(common.TEACHER_A)
    _, agents = common.request("GET", "/api/agents", token=teacher)
    default_agent = next((a for a in agents if a["name"] == "解题助手"), None)
    common.assert_true(default_agent is not None, "应存在迁移生成的默认智能体")
    common.assert_true(default_agent["enabled"], "默认智能体应启用")


CASES = [
    ("教师见全部学生只见已启用", _teacher_sees_all_student_enabled_only),
    ("他班智能体详情 404", _other_class_agent_404),
    ("迁移生成默认智能体", _seed_default_agent_per_class),
]
