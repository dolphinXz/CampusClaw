package com.campusclaw.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusclaw.domain.AiSkill;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 确定性技能路由单测：零分、最高分、同分取小 id、空候选、解题引导禁令。 */
class SkillRouterTest {

    private AiSkill skill(long id, String name, String conditions) {
        AiSkill skill = new AiSkill(1L, null, name, "说明", "正文", true,
                conditions, "", "knowledge_search", null);
        ReflectionTestUtils.setField(skill, "id", id);
        return skill;
    }

    @Test
    void zeroScoreSelectsNothing() {
        AiSkill s = skill(1L, "英语技能", "grammar,present-tense");
        assertThat(SkillRouter.route("分数加减法", List.of(s))).isNull();
    }

    @Test
    void highestScoreWins() {
        AiSkill math = skill(1L, "数学解题", "分数,通分");
        AiSkill english = skill(2L, "英语技能", "grammar");
        // 数学技能命中 2 个词
        AiSkill chosen = SkillRouter.route("分数加减法需要先通分", List.of(english, math));
        assertThat(chosen).isSameAs(math);
    }

    @Test
    void tieBreaksBySmallerId() {
        AiSkill a = skill(3L, "技能A", "方程");
        AiSkill b = skill(7L, "技能B", "方程");
        // 同分（各命中 1 词）→ id 小者
        assertThat(SkillRouter.route("解方程", List.of(b, a))).isSameAs(a);
    }

    @Test
    void routingIsRepeatable() {
        AiSkill a = skill(3L, "技能A", "方程,一元");
        AiSkill b = skill(7L, "技能B", "方程");
        String question = "一元二次方程";
        AiSkill first = SkillRouter.route(question, List.of(a, b));
        AiSkill second = SkillRouter.route(question, List.of(b, a));
        assertThat(first).isSameAs(second);
    }

    @Test
    void emptyCandidatesOrQuestionSelectsNothing() {
        assertThat(SkillRouter.route("任意问题", List.of())).isNull();
        assertThat(SkillRouter.route("  ", List.of(skill(1L, "x", "y")))).isNull();
        assertThat(SkillRouter.route(null, List.of(skill(1L, "x", "y")))).isNull();
    }

    @Test
    void caseInsensitiveSubstringMatching() {
        AiSkill s = skill(1L, "英文写作", "Paragraph,Essay");
        assertThat(SkillRouter.route("write a paragraph now", List.of(s))).isSameAs(s);
        assertThat(SkillRouter.route("WRITE AN ESSAY", List.of(s))).isSameAs(s);
    }

    @Test
    void solutionGuideNameRequiresNoFinalAnswer() {
        AiSkill guide = skill(1L, "数学解题引导", "分数");
        AiSkill other = skill(2L, "写作", "作文");
        assertThat(SkillRouter.requiresNoFinalAnswer(guide)).isTrue();
        assertThat(SkillRouter.requiresNoFinalAnswer(other)).isFalse();
        assertThat(SkillRouter.requiresNoFinalAnswer(null)).isFalse();
    }
}
