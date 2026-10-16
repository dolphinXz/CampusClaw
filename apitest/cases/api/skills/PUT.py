# -*- coding: utf-8 -*-
"""PUT /api/skills/{id}：rawText 重解析全量覆盖；学生 403；他班 404；解析错误 400。"""
import common

VALID_FILE = """名称：语法引导
适用条件：主语,谓语,宾语
资料：语文
工具：knowledge_search
启用：是

先判断句子成分，再提示修改方向。"""


def _create_skill(token, raw_text):
    status, data = common.request("POST", "/api/skills", token=token,
                                  json_body={"rawText": raw_text})
    common.assert_eq(status, 200, "创建技能状态码")
    return data["id"]


def _teacher_reparses_and_overwrites(ctx):
    token = common.login(common.TEACHER_A)
    skill_id = _create_skill(token, VALID_FILE)

    # 更新：改正文与启用开关（整文件重传）
    disabled = VALID_FILE.replace("启用：是", "启用：否").replace("先判断句子成分", "先判断主谓宾")
    status, data = common.request("PUT", "/api/skills/%d" % skill_id, token=token,
                                  json_body={"rawText": disabled})
    common.assert_eq(status, 200, "更新状态码")
    common.assert_eq(data["name"], "语法引导", "名称重解析一致")
    common.assert_eq(data["enabled"], False, "启用开关生效")
    common.assert_true("先判断主谓宾" in data["content"], "正文全量覆盖")

    row = common.db_one("SELECT name, enabled, content, raw_text FROM ai_skills WHERE id = %s",
                        (skill_id,))
    common.assert_eq(row["name"], "语法引导", "名称落库")
    common.assert_eq(bool(row["enabled"]), False, "启用状态落库")
    common.assert_eq(row["raw_text"], disabled, "原文落库")

    common.request("DELETE", "/api/skills/%d" % skill_id, token=token)


def _parse_error_400_readable(ctx):
    token = common.login(common.TEACHER_A)
    # 缺少空行分隔正文
    status, data = common.request("POST", "/api/skills", token=token,
                                  json_body={"rawText": "名称：残缺技能\n适用条件：x\n启用：是"})
    common.assert_eq(status, 400, "缺正文应 400")
    common.assert_true("正文" in data.get("message", ""), "错误信息应可读：%s" % data)

    # 非法工具
    bad_tool = VALID_FILE.replace("knowledge_search", "shell_exec")
    status, data = common.request("POST", "/api/skills", token=token,
                                  json_body={"rawText": bad_tool})
    common.assert_eq(status, 400, "非法工具应 400")
    common.assert_true("knowledge_search" in data.get("message", ""), "错误应提示允许的工具")

    # 未知字段
    bad_key = VALID_FILE.replace("适用条件：主语", "难度：主语\n适用条件：主语")
    status, data = common.request("POST", "/api/skills", token=token,
                                  json_body={"rawText": bad_key})
    common.assert_eq(status, 400, "未知字段应 400")
    common.assert_true("未知字段" in data.get("message", ""), "错误应指出未知字段")


def _student_write_403(ctx):
    teacher = common.login(common.TEACHER_A)
    skill_id = _create_skill(teacher, VALID_FILE)
    before = common.db_one("SELECT name FROM ai_skills WHERE id = %s", (skill_id,))

    student = common.login(common.STUDENT_A)
    status, _ = common.request("PUT", "/api/skills/%d" % skill_id, token=student,
                               json_body={"rawText": VALID_FILE.replace("语法引导", "越权改名")})
    common.assert_eq(status, 403, "学生写应 403")

    after = common.db_one("SELECT name FROM ai_skills WHERE id = %s", (skill_id,))
    common.assert_eq(after, before, "失败请求不得改变数据库")
    common.request("DELETE", "/api/skills/%d" % skill_id, token=teacher)


def _other_class_skill_404(ctx):
    teacher_b = common.login(common.TEACHER_B)
    other_id = _create_skill(teacher_b, VALID_FILE)
    teacher_a = common.login(common.TEACHER_A)
    status, _ = common.request("PUT", "/api/skills/%d" % other_id, token=teacher_a,
                               json_body={"rawText": VALID_FILE.replace("语法引导", "他班改名")})
    common.assert_eq(status, 404, "他班技能 id 应 404（与不存在一致）")
    common.request("DELETE", "/api/skills/%d" % other_id, token=teacher_b)


def _duplicate_name_400(ctx):
    token = common.login(common.TEACHER_A)
    skill_id = _create_skill(token, VALID_FILE)
    status, data = common.request("POST", "/api/skills", token=token,
                                  json_body={"rawText": VALID_FILE})
    common.assert_eq(status, 400, "班内重名应 400")
    common.assert_true("同名" in data.get("message", ""), "错误应说明重名：%s" % data)
    common.request("DELETE", "/api/skills/%d" % skill_id, token=token)


CASES = [
    ("教师重解析全量覆盖且落库一致", _teacher_reparses_and_overwrites),
    ("解析错误返回可读 400", _parse_error_400_readable),
    ("学生写被拒绝且无写入", _student_write_403),
    ("他班技能 id 返回 404", _other_class_skill_404),
    ("班内重名返回 400", _duplicate_name_400),
]
