# -*- coding: utf-8 -*-
"""PUT/GET /api/agents/{id}/skills|materials：全量替换与回显；越界 400 无部分写入；学生 403。"""
import common


def _replace_and_echo(ctx):
    teacher = common.login(common.TEACHER_A)
    _, agents = common.request("GET", "/api/agents", token=teacher)
    agent_id = agents[0]["id"]

    _, skills = common.request("GET", "/api/skills", token=teacher)
    _, materials = common.request("GET", "/api/materials", token=teacher)
    skill_ids = [s["id"] for s in skills[:2]]
    material_ids = [m["id"] for m in materials[:2]]

    status, _ = common.request("PUT", "/api/agents/%d/skills" % agent_id, token=teacher,
                               json_body={"ids": skill_ids})
    common.assert_eq(status, 200, "绑定技能状态码")
    status, _ = common.request("PUT", "/api/agents/%d/materials" % agent_id, token=teacher,
                               json_body={"ids": material_ids})
    common.assert_eq(status, 200, "绑定材料状态码")

    status, bindings = common.request("GET", "/api/agents/%d/bindings" % agent_id, token=teacher)
    common.assert_eq(status, 200, "回显状态码")
    common.assert_eq(sorted(s["id"] for s in bindings["skills"]), sorted(skill_ids), "技能绑定回显")
    common.assert_eq(sorted(m["id"] for m in bindings["materials"]), sorted(material_ids),
                      "材料绑定回显")

    # 数据库一致
    rows = common.db_query("SELECT skill_id FROM assistant_skills WHERE assistant_id = %s",
                           (agent_id,))
    common.assert_eq(sorted(r[0] for r in rows[1]), sorted(skill_ids), "技能绑定落库")

    # 恢复默认绑定（种子：全部启用技能 + 全班材料）
    all_enabled = [s["id"] for s in skills if s["enabled"]]
    all_materials = [m["id"] for m in materials]
    common.request("PUT", "/api/agents/%d/skills" % agent_id, token=teacher,
                   json_body={"ids": all_enabled})
    common.request("PUT", "/api/agents/%d/materials" % agent_id, token=teacher,
                   json_body={"ids": all_materials})


def _out_of_class_ids_400_no_partial(ctx):
    teacher_a = common.login(common.TEACHER_A)
    teacher_b = common.login(common.TEACHER_B)
    _, agents_b = common.request("GET", "/api/agents", token=teacher_b)
    _, skills_b = common.request("GET", "/api/skills", token=teacher_b)
    _, materials_b = common.request("GET", "/api/materials", token=teacher_b)
    foreign_skill = skills_b[0]["id"]
    foreign_material = materials_b[0]["id"]

    _, agents_a = common.request("GET", "/api/agents", token=teacher_a)
    agent_id = agents_a[0]["id"]
    _, skills_a = common.request("GET", "/api/skills", token=teacher_a)
    own_skill = skills_a[0]["id"]
    before = common.db_query("SELECT skill_id FROM assistant_skills WHERE assistant_id = %s",
                             (agent_id,))

    # 混入他班技能 id → 400，且不产生部分绑定
    status, data = common.request("PUT", "/api/agents/%d/skills" % agent_id, token=teacher_a,
                                  json_body={"ids": [own_skill, foreign_skill]})
    common.assert_eq(status, 400, "他班技能 id 应 400")
    common.assert_true("本班" in data.get("message", ""), "错误应说明越界：%s" % data)
    after = common.db_query("SELECT skill_id FROM assistant_skills WHERE assistant_id = %s",
                            (agent_id,))
    common.assert_eq(after, before, "失败请求不得产生部分绑定")

    # 混入他班材料 id → 400
    _, materials_a = common.request("GET", "/api/materials", token=teacher_a)
    own_material = materials_a[0]["id"]
    status, _ = common.request("PUT", "/api/agents/%d/materials" % agent_id, token=teacher_a,
                               json_body={"ids": [own_material, foreign_material]})
    common.assert_eq(status, 400, "他班材料 id 应 400")

    # 不存在的 id → 400（与他班一致，不泄露存在性差异）
    status, _ = common.request("PUT", "/api/agents/%d/skills" % agent_id, token=teacher_a,
                               json_body={"ids": [99999999]})
    common.assert_eq(status, 400, "不存在 id 应 400")


def _student_bind_403(ctx):
    teacher = common.login(common.TEACHER_A)
    _, agents = common.request("GET", "/api/agents", token=teacher)
    agent_id = agents[0]["id"]
    before = common.db_one("SELECT COUNT(*) AS n FROM assistant_skills WHERE assistant_id = %s",
                           (agent_id,))

    student = common.login(common.STUDENT_A)
    status, _ = common.request("PUT", "/api/agents/%d/skills" % agent_id, token=student,
                               json_body={"ids": []})
    common.assert_eq(status, 403, "学生绑定应 403")
    status, _ = common.request("GET", "/api/agents/%d/bindings" % agent_id, token=student)
    common.assert_eq(status, 403, "学生回显绑定应 403")

    after = common.db_one("SELECT COUNT(*) AS n FROM assistant_skills WHERE assistant_id = %s",
                          (agent_id,))
    common.assert_eq(after, before, "403 不得改变绑定")


CASES = [
    ("全量替换绑定且回显落库一致", _replace_and_echo),
    ("越界 id 返回 400 且无部分写入", _out_of_class_ids_400_no_partial),
    ("学生绑定被拒绝", _student_bind_403),
]
