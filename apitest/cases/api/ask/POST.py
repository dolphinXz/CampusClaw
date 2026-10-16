# -*- coding: utf-8 -*-
"""POST /api/ask（第 4 课旧链路回归）：无依据固定句短路，不被第 5 课流式口径改变。"""
import common


def _no_evidence_fixed_sentence(ctx):
    token = common.login(common.STUDENT_A)
    # 与新链路无关：旧接口无会话概念，班级取自认证主体
    status, data = common.request("POST", "/api/ask", token=token,
                                  json_body={"question": "火星上有没有外星人 量子纠缠定律"})
    common.assert_eq(status, 200, "状态码")
    common.assert_eq(data.get("basis"), "none", "无依据 basis=none")
    common.assert_eq(data.get("citations"), [], "无依据空引用")
    # 旧链路核心口径：固定句，不调用模型
    common.assert_eq(data.get("answer"), "资料中未找到相关内容", "无依据为固定句")


def _with_evidence_has_citations(ctx):
    token = common.login(common.STUDENT_A)
    status, data = common.request("POST", "/api/ask", token=token,
                                  json_body={"question": "分数加减法要先做什么？"})
    common.assert_eq(status, 200, "状态码")
    common.assert_true(data.get("answer"), "回答非空")
    if data.get("basis") == "found":
        common.assert_true(data.get("citations"), "命中应有引用")
        for i, c in enumerate(data["citations"]):
            common.assert_eq(c.get("refNumber"), i + 1, "编号从 1 连续")


def _empty_question_400(ctx):
    token = common.login(common.STUDENT_A)
    status, _ = common.request("POST", "/api/ask", token=token, json_body={"question": "  "})
    common.assert_eq(status, 400, "空问题应 400")


def _unauthorized_401(ctx):
    status, _ = common.request("POST", "/api/ask", json_body={"question": "hi"})
    common.assert_eq(status, 401, "无 token 应 401")


CASES = [
    ("无依据固定句短路", _no_evidence_fixed_sentence),
    ("有依据返回引用", _with_evidence_has_citations),
    ("空问题 400", _empty_question_400),
    ("未授权 401", _unauthorized_401),
]
