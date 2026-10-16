package com.campusclaw.service;

import com.campusclaw.domain.AiSkill;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 确定性技能路由（纯函数）：只考虑智能体绑定且启用的技能；
 * 问题与关键词统一小写后按子串命中计分；最高分胜出，同分取 DB ID 小者，全零不选。
 * 每轮至多一个技能。
 */
public final class SkillRouter {

    private SkillRouter() {
    }

    /** 候选技能须已过滤为「绑定 ∩ 启用」。 */
    public static AiSkill route(String question, List<AiSkill> candidates) {
        if (question == null || question.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String q = question.toLowerCase(Locale.ROOT);
        AiSkill best = null;
        int bestScore = 0;
        // 稳定序：按 id 升序遍历，严格大于才替换 → 同分保留 id 小者
        List<AiSkill> ordered = candidates.stream()
                .sorted(Comparator.comparing(AiSkill::getId))
                .toList();
        for (AiSkill skill : ordered) {
            int score = score(q, skill);
            if (score > bestScore) {
                bestScore = score;
                best = skill;
            }
        }
        return bestScore == 0 ? null : best;
    }

    static int score(String lowerQuestion, AiSkill skill) {
        int score = 0;
        for (String keyword : SkillFileParser.splitConditions(skill.getConditions())) {
            if (lowerQuestion.contains(keyword.toLowerCase(Locale.ROOT))) {
                score++;
            }
        }
        return score;
    }

    /** 命中技能名含「解题引导」时的额外禁令（系统提示追加）。 */
    public static boolean requiresNoFinalAnswer(AiSkill skill) {
        return skill != null && skill.getName().contains("解题引导");
    }
}
