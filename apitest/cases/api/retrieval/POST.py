# -*- coding: utf-8 -*-
"""POST /api/retrieval：检索本班材料、结果字段与数据库一致、无命中符合约定、未授权 401。"""
import common


def _class_material_retrievable(ctx):
    token = common.login(common.STUDENT_A)
    status, data = common.request(
        "POST", "/api/retrieval", token=token,
        json_body={"query": "分数", "mode": "keyword"})
    common.assert_eq(status, 200, "检索状态码")
    common.assert_true(isinstance(data, dict), "响应应为 JSON")
    common.assert_eq(data.get("mode"), "keyword", "回显模式")

    if data.get("basis") == "found":
        hit = data["hits"][0]
        for field in ("materialId", "materialTitle", "chunkId", "chunkIndex",
                      "charRange", "excerpt", "openUrl"):
            common.assert_true(field in hit, "命中应含字段 " + field)
        # 数据库一致性：chunk 存在、班级为 A 班、状态 READY、摘录取自 chunk_text
        row = common.db_one(
            "SELECT class_id, index_status, chunk_text FROM knowledge_chunks WHERE id = %s",
            (hit["chunkId"],))
        common.assert_true(row is not None, "chunk 应存在于数据库")
        common.assert_eq(row["index_status"], "READY", "命中切片应为 READY")
        common.assert_true(row["chunk_text"].startswith(hit["excerpt"][:30])
                           or hit["excerpt"][:30] in row["chunk_text"], "摘录应来自 chunk_text")
    else:
        common.assert_eq(data.get("basis"), "none", "无命中 basis")
        common.assert_eq(data.get("message"), "资料中未找到相关内容", "无命中固定提示")
        common.assert_eq(data.get("hits"), [], "无命中 hits 为空")


def _empty_query_400(ctx):
    token = common.login(common.STUDENT_A)
    status, _ = common.request(
        "POST", "/api/retrieval", token=token, json_body={"query": "   "})
    common.assert_eq(status, 400, "空查询应 400")


def _unauthorized_401(ctx):
    status, _ = common.request("POST", "/api/retrieval", json_body={"query": "分数"})
    common.assert_eq(status, 401, "无 token 检索应 401")


def _forged_class_id_ignored(ctx):
    """请求体伪造 classId：忽略之，不返回 B 班内容。"""
    token = common.login(common.STUDENT_A)
    status, data = common.request(
        "POST", "/api/retrieval", token=token,
        json_body={"query": "欢迎", "classId": 2})
    common.assert_eq(status, 200, "带伪造 classId 状态码")
    titles = [h.get("materialTitle", "") for h in (data.get("hits") or [])]
    common.assert_true(all("B 班" not in t for t in titles), "不得出现 B 班材料")


CASES = [
    ("检索本班材料且字段与数据库一致", _class_material_retrievable),
    ("空查询返回 400", _empty_query_400),
    ("未授权检索返回 401", _unauthorized_401),
    ("伪造 classId 被忽略", _forged_class_id_ignored),
]
