package com.campusclaw.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 智能体↔技能 绑定（复合主键）。 */
@Entity
@Table(name = "assistant_skills")
@IdClass(AssistantBinding.SkillKey.class)
public class AssistantBinding {

    @Id
    @Column(name = "assistant_id")
    private Long assistantId;

    @Id
    @Column(name = "skill_id")
    private Long skillId;

    protected AssistantBinding() {
    }

    public AssistantBinding(Long assistantId, Long skillId) {
        this.assistantId = assistantId;
        this.skillId = skillId;
    }

    public Long getAssistantId() {
        return assistantId;
    }

    public Long getSkillId() {
        return skillId;
    }

    public static class SkillKey implements Serializable {
        private Long assistantId;
        private Long skillId;

        public SkillKey() {
        }

        public SkillKey(Long assistantId, Long skillId) {
            this.assistantId = assistantId;
            this.skillId = skillId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SkillKey other)) return false;
            return Objects.equals(assistantId, other.assistantId) && Objects.equals(skillId, other.skillId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(assistantId, skillId);
        }
    }
}
