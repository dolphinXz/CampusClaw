# -*- coding: utf-8 -*-
"""DELETE /api/skills/{id}：删除同步级联解绑；学生 403；他班 404。"""
import common

RAW_FILE = """名称：临时测试技能
适用条件：测试关键词甲,测试关键词乙
工具：knowledge_search
启用：是

仅用于 apitest 的临时技能，测完即删。"""


def _create(token, name="临时测试技能"):
    status, data = common.request("POST", "/api/skills", token=token,
                                  json_body={"rawText": RAW_FILE.replace("临时测试技能", name)})
    common.assert_eq(status, 200, "创建状态码")
    return data["id"]


def _delete_cascades_bindings(ctx):
    teacher = common.login(common.TEACHER_A)
    skill_id = _create(teacher)

    # 绑定到本班默认智能体
    _, agents = common.request("GET", "/api/agents", token=teacher)
    agent = next(a for a in agents if a["enabled"])
    _, bindings = common.request("GET", "/api/agents/%d/bindings" % agent["id"], token=teacher)
    original = [s["id"] for s in bindings["skills"]]
    common.request("PUT", "/api/agents/%d/skills" % agent["id"], token=teacher,
                   json_body={"ids": original + [skill_id]})
    _, bindings = common.request("GET", "/api/agents/%d/bindings" % agent["id"], token=teacher)
    common.assert_true(skill_id in [s["id"] for s in bindings["skills"]], "绑定后应回显")

    # 删除技能 → 绑定同步消失
    status, _ = common.request("DELETE", "/api/skills/%d" % skill_id, token=teacher)
    common.assert_eq(status, 200, "删除状态码")
    row = common.db_one("SELECT id FROM ai_skills WHERE id = %s", (skill_id,))
    common.assert_true(row is None, "技能应已删除")
    bind_row = common.db_one(
        "SELECT COUNT(*) AS n FROM assistant_skills WHERE skill_id = %s", (skill_id,))
    common.assert_eq(int(bind_row["n"]), 0, "绑定应级联删除")

    # 恢复原绑定，避免影响后续用例
    common.request("PUT", "/api/agents/%d/skills" % agent["id"], token=teacher,
                   json_body={"ids": original})


def _student_delete_403(ctx):
    teacher = common.login(common.TEACHER_A)
    skill_id = _create(teacher, "临时测试技能二")
    student = common.login(common.STUDENT_A)
    status, _ = common.request("DELETE", "/api/skills/%d" % skill_id, token=student)
    common.assert_eq(status, 403, "学生删除应 403")
    row = common.db_one("SELECT id FROM ai_skills WHERE id = %s", (skill_id,))
    common.assert_true(row is not None, "403 不得删除")
    common.request("DELETE", "/api/skills/%d" % skill_id, token=teacher)


def _other_class_skill_404(ctx):
    teacher_b = common.login(common.TEACHER_B)
    other_id = _create(teacher_b, "临时测试技能三")
    teacher_a = common.login(common.TEACHER_A)
    status, _ = common.request("DELETE", "/api/skills/%d" % other_id, token=teacher_a)
    common.assert_eq(status, 404, "他班技能应 404")
    row = common.db_one("SELECT id FROM ai_skills WHERE id = %s", (other_id,))
    common.assert_true(row is not None, "他班删除不得生效")
    common.request("DELETE", "/api/skills/%d" % other_id, token=teacher_b)


CASES = [
    ("删除技能级联解绑", _delete_cascades_bindings),
    ("学生删除被拒绝", _student_delete_403),
    ("他班技能删除返回 404", _other_class_skill_404),
]
