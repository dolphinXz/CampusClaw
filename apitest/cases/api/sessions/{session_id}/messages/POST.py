# -*- coding: utf-8 -*-
"""
POST /api/sessions/{id}/messages：SSE 流式回答（智能体链路）。
核对：meta 先行、delta 只含新增、done 落库一致、无依据仍生成（引用为空）、
空问题 400、他人会话 404、未授权 401。
与旧 /api/ask 不同：检索无命中时本链路不返回固定句，仍按智能体生成。
"""
import common


def _make_session(token, title=None):
    _, agents = common.request("GET", "/api/agents", token=token)
    agent = next(a for a in agents if a["enabled"])
    body = {"agentId": agent["id"]}
    if title:
        body["title"] = title
    status, data = common.request("POST", "/api/sessions", token=token, json_body=body)
    common.assert_eq(status, 200, "创建会话状态码")
    return data["id"]


def _stream_meta_then_delta_done(ctx):
    token = common.login(common.STUDENT_A)
    session_id = _make_session(token, "SSE 验证会话")

    status, events = common.sse_request(
        "/api/sessions/%d/messages" % session_id, token=token,
        json_body={"question": "分数加减法要先做什么？"})
    common.assert_eq(status, 200, "SSE 状态码")
    common.assert_true(isinstance(events, list), "200 时应为事件列表")

    names = [e["event"] for e in events]
    common.assert_true(names, "应至少收到事件")
    common.assert_eq(names[0], "meta", "meta 必须最先到达")
    common.assert_true("done" in names or "error" in names, "应以 done/error 收尾")

    metas = [e["data"] for e in events if e["event"] == "meta"]
    dones = [e["data"] for e in events if e["event"] == "done"]
    errors = [e["data"] for e in events if e["event"] == "error"]
    deltas = [e["data"].get("text", "") for e in events if e["event"] == "delta"]

    common.assert_eq(len(metas), 1, "应恰好一个 meta")
    meta = metas[0]
    common.assert_true(meta.get("status") in ("ok", "no_evidence"), "meta 含状态")
    common.assert_true("skill" in meta, "meta 含技能字段（可为 null）")
    common.assert_true(isinstance(meta.get("citations"), list), "meta 含引用数组")

    common.assert_true(not errors, "本轮不应有 error 事件：%s" % errors)
    common.assert_eq(len(dones), 1, "应恰好一个 done 事件")
    common.assert_true(isinstance(dones[0].get("messageId"), int), "done 应含 messageId")

    full_answer = "".join(deltas)
    common.assert_true(full_answer.strip(), "拼接回答非空")

    # 引用编号一致性：有引用时正文 [n] 不得超出引用数量
    citations = meta["citations"]
    if meta["status"] == "ok":
        common.assert_true(citations, "命中时引用数组非空")
        for i, c in enumerate(citations):
            common.assert_eq(c.get("refNumber"), i + 1, "引用从 1 连续编号")
    else:
        # 无依据仍生成：引用为空，正文不得出现 [n] 编号
        common.assert_eq(citations, [], "无依据 citations 为空")
        common.assert_true("[1]" not in full_answer, "无依据正文不得出现 [n] 编号")

    # 数据库一致性：assistant 行内容 == 流式拼接内容
    row = common.db_one(
        "SELECT role, content, basis, citations, skill_name FROM chat_messages "
        "WHERE id = %s AND session_id = %s", (dones[0]["messageId"], session_id))
    common.assert_true(row is not None, "assistant 消息应已落库")
    common.assert_eq(row["role"], "assistant", "角色为 assistant")
    common.assert_eq(row["content"], full_answer.strip(), "落库内容与流式拼接一致")
    expected_basis = "none" if meta["status"] == "no_evidence" else "found"
    common.assert_eq(row["basis"], expected_basis, "落库 basis 与 meta 状态一致")
    if meta.get("skill"):
        common.assert_eq(row["skill_name"], meta["skill"]["name"], "命中技能名落库")
    else:
        common.assert_true(row["skill_name"] is None, "未命中技能时 skill_name 为空")

    # 学生消息也在库中
    user_row = common.db_one(
        "SELECT COUNT(*) AS n FROM chat_messages WHERE session_id = %s AND role = 'user'",
        (session_id,))
    common.assert_eq(int(user_row["n"]), 1, "学生消息应落库")

    # 首轮问题后标题更新为问题前 24 字符
    session_row = common.db_one("SELECT title FROM chat_sessions WHERE id = %s", (session_id,))
    common.assert_eq(session_row["title"], "分数加减法要先做什么？", "首轮后标题为问题文本")

    # 刷新可恢复：详情接口按序返回消息，assistant 行带技能名与引用
    status, detail = common.request("GET", "/api/sessions/%d" % session_id, token=token)
    common.assert_eq(status, 200, "详情状态码")
    roles = [m["role"] for m in detail["messages"]]
    common.assert_eq(roles, ["user", "assistant"], "详情按序含两轮消息")
    common.assert_eq(detail["messages"][1].get("skillName"), meta.get("skill", {}) and
                     (meta["skill"]["name"] if meta.get("skill") else None),
                     "详情回显命中技能名")


def _no_evidence_still_generates(ctx):
    token = common.login(common.STUDENT_A)
    session_id = _make_session(token, "无依据验证会话")
    # 用词避开语料真实出现的词，确保零命中
    status, events = common.sse_request(
        "/api/sessions/%d/messages" % session_id, token=token,
        json_body={"question": "火星上有没有外星人 量子纠缠定律"})
    common.assert_eq(status, 200, "无依据也是 200 流")
    metas = [e["data"] for e in events if e["event"] == "meta"]
    dones = [e["data"] for e in events if e["event"] == "done"]
    common.assert_eq(len(metas), 1, "应有 meta")
    common.assert_eq(metas[0]["status"], "no_evidence", "无依据 meta.status=no_evidence")
    common.assert_eq(metas[0]["citations"], [], "无依据空引用")
    common.assert_eq(len(dones), 1, "应有 done")
    deltas = "".join(e["data"].get("text", "") for e in events if e["event"] == "delta")
    # 与旧 /api/ask 的固定句不同：本链路仍按智能体生成
    common.assert_true(deltas.strip(), "无依据仍生成正文")
    common.assert_true("[1]" not in deltas, "正文不得出现 [n] 编号")


def _empty_question_400(ctx):
    token = common.login(common.STUDENT_A)
    session_id = _make_session(token, "空问题验证会话")
    status, _ = common.sse_request(
        "/api/sessions/%d/messages" % session_id, token=token, json_body={"question": "  "})
    common.assert_eq(status, 400, "空问题应 400")
    row = common.db_one("SELECT COUNT(*) AS n FROM chat_messages WHERE session_id = %s",
                        (session_id,))
    common.assert_eq(int(row["n"]), 0, "400 不得产生消息写入")


def _others_session_404(ctx):
    token_b = common.login(common.STUDENT_B)
    session_b = _make_session(token_b, "B 班私有会话")
    token_a = common.login(common.STUDENT_A)
    status, _ = common.sse_request(
        "/api/sessions/%d/messages" % session_b, token=token_a,
        json_body={"question": "能访问吗？"})
    common.assert_eq(status, 404, "他人/他班会话应 404（与不存在一致）")

    status, _ = common.request("GET", "/api/sessions/%d" % session_b, token=token_a)
    common.assert_eq(status, 404, "读取他人会话也 404")


def _unauthorized_401(ctx):
    status, _ = common.sse_request("/api/sessions/1/messages", json_body={"question": "hi"})
    common.assert_eq(status, 401, "无 token 应 401")


CASES = [
    ("meta 先行且落库一致", _stream_meta_then_delta_done),
    ("无依据仍生成且引用为空", _no_evidence_still_generates),
    ("空问题 400 且无写入", _empty_question_400),
    ("他人会话 404 与不存在一致", _others_session_404),
    ("未授权发送返回 401", _unauthorized_401),
]
