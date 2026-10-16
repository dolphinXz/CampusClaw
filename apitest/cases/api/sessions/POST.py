# -*- coding: utf-8 -*-
"""POST /api/sessions：必须选定本班已启用智能体；标题默认「新的提问」；列表只含本人。"""
import common


def _student_creates_session_with_agent(ctx):
    token = common.login(common.STUDENT_A)
    _, agents = common.request("GET", "/api/agents", token=token)
    agent = next(a for a in agents if a["enabled"])
    common.assert_true(agents, "本班应有已启用智能体")

    status, data = common.request("POST", "/api/sessions", token=token,
                                  json_body={"agentId": agent["id"], "title": "apitest 会话"})
    common.assert_eq(status, 200, "创建状态码")
    common.assert_true(isinstance(data.get("id"), int), "响应应含会话 id")
    common.assert_eq(data.get("title"), "apitest 会话", "标题回显")

    row = common.db_one("SELECT owner_id, class_id, title, agent_id FROM chat_sessions WHERE id = %s",
                        (data["id"],))
    common.assert_true(row is not None, "数据库应有会话记录")
    common.assert_eq(row["title"], "apitest 会话", "标题与数据库一致")
    common.assert_eq(row["agent_id"], agent["id"], "会话锁定智能体")

    user = common.db_one("SELECT id, class_id FROM users WHERE username = %s",
                         (common.STUDENT_A[0],))
    common.assert_eq(row["owner_id"], user["id"], "归属创建者")
    common.assert_eq(row["class_id"], user["class_id"], "班级为创建者班级")


def _default_title_then_first_question(ctx):
    token = common.login(common.STUDENT_A)
    _, agents = common.request("GET", "/api/agents", token=token)
    agent = next(a for a in agents if a["enabled"])

    status, data = common.request("POST", "/api/sessions", token=token,
                                  json_body={"agentId": agent["id"]})
    common.assert_eq(status, 200, "缺省标题创建成功")
    common.assert_eq(data["title"], "新的提问", "缺省标题为「新的提问」")

    # 空问题发送 → 400 且不改标题
    common.sse_request("/api/sessions/%d/messages" % data["id"], token=token,
                       json_body={"question": "  "})
    row = common.db_one("SELECT title FROM chat_sessions WHERE id = %s", (data["id"],))
    common.assert_eq(row["title"], "新的提问", "空问题不改标题")


def _missing_or_invalid_agent(ctx):
    token = common.login(common.STUDENT_A)
    # 缺 agentId → 400（校验错误）
    status, _ = common.request("POST", "/api/sessions", token=token, json_body={})
    common.assert_eq(status, 400, "缺 agentId 应 400")

    # 不存在的 agentId → 404
    status, _ = common.request("POST", "/api/sessions", token=token,
                               json_body={"agentId": 99999999})
    common.assert_eq(status, 404, "不存在智能体应 404")

    # 他班智能体 → 404（与不存在一致，不泄露存在性）
    teacher_b = common.login(common.TEACHER_B)
    _, agents_b = common.request("GET", "/api/agents", token=teacher_b)
    status, _ = common.request("POST", "/api/sessions", token=token,
                               json_body={"agentId": agents_b[0]["id"]})
    common.assert_eq(status, 404, "他班智能体应 404")

    # 已停用智能体 → 404；停用前先建会话验证老会话仍可用
    teacher_a = common.login(common.TEACHER_A)
    _, agents_a = common.request("GET", "/api/agents", token=teacher_a)
    target = next(a for a in agents_a if a["enabled"])
    status, old_session = common.request("POST", "/api/sessions", token=token,
                                         json_body={"agentId": target["id"]})
    common.assert_eq(status, 200, "停用前建会话成功")

    common.request("PUT", "/api/agents/%d" % target["id"], token=teacher_a,
                   json_body={"enabled": False})
    try:
        status, _ = common.request("POST", "/api/sessions", token=token,
                                   json_body={"agentId": target["id"]})
        common.assert_eq(status, 404, "停用智能体不能新建会话")

        # 停用后老会话仍可继续使用（发消息不因智能体停用而失败）
        status, events = common.sse_request(
            "/api/sessions/%d/messages" % old_session["id"], token=token,
            json_body={"question": "停用后的老会话还能回答吗"})
        common.assert_eq(status, 200, "停用智能体的老会话仍可继续")
        common.assert_true(isinstance(events, list) and any(
            e["event"] == "done" for e in events), "老会话应正常完成")
    finally:
        common.request("PUT", "/api/agents/%d" % target["id"], token=teacher_a,
                       json_body={"enabled": True})


def _list_only_own_sessions(ctx):
    token_b = common.login(common.STUDENT_B)
    _, agents_b = common.request("GET", "/api/agents", token=token_b)
    agent_b = next(a for a in agents_b if a["enabled"])
    _, created = common.request("POST", "/api/sessions", token=token_b,
                                json_body={"agentId": agent_b["id"], "title": "B 班会话-apitest"})
    status, data = common.request("GET", "/api/sessions", token=token_b)
    common.assert_eq(status, 200, "列表状态码")
    ids = [s["id"] for s in data]
    common.assert_true(created["id"] in ids, "列表应含本人刚建会话")

    # A 班学生列表不含 B 班会话
    token_a = common.login(common.STUDENT_A)
    _, data_a = common.request("GET", "/api/sessions", token=token_a)
    a_ids = [s["id"] for s in data_a]
    common.assert_true(created["id"] not in a_ids, "A 班列表不得含 B 班会话")


def _session_detail_locks_agent(ctx):
    token = common.login(common.STUDENT_A)
    _, agents = common.request("GET", "/api/agents", token=token)
    agent = next(a for a in agents if a["enabled"])
    _, created = common.request("POST", "/api/sessions", token=token,
                                json_body={"agentId": agent["id"], "title": "锁定验证"})

    status, detail = common.request("GET", "/api/sessions/%d" % created["id"], token=token)
    common.assert_eq(status, 200, "详情状态码")
    common.assert_eq(detail["agentId"], agent["id"], "详情回显锁定的智能体")
    common.assert_eq(detail["agentName"], agent["name"], "详情回显智能体名称")
    common.assert_true(isinstance(detail["messages"], list), "详情含消息数组")


CASES = [
    ("学生选定智能体创建会话", _student_creates_session_with_agent),
    ("缺省标题与空问题语义", _default_title_then_first_question),
    ("无效智能体 404 与停用语义", _missing_or_invalid_agent),
    ("会话列表只含本人", _list_only_own_sessions),
    ("详情回显锁定智能体", _session_detail_locks_agent),
]
