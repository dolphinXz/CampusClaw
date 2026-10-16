package com.campusclaw.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 智能体↔材料 绑定（复合主键）。 */
@Entity
@Table(name = "assistant_materials")
@IdClass(AssistantMaterialBinding.MaterialKey.class)
public class AssistantMaterialBinding {

    @Id
    @Column(name = "assistant_id")
    private Long assistantId;

    @Id
    @Column(name = "material_id")
    private Long materialId;

    protected AssistantMaterialBinding() {
    }

    public AssistantMaterialBinding(Long assistantId, Long materialId) {
        this.assistantId = assistantId;
        this.materialId = materialId;
    }

    public Long getAssistantId() {
        return assistantId;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public static class MaterialKey implements Serializable {
        private Long assistantId;
        private Long materialId;

        public MaterialKey() {
        }

        public MaterialKey(Long assistantId, Long materialId) {
            this.assistantId = assistantId;
            this.materialId = materialId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MaterialKey other)) return false;
            return Objects.equals(assistantId, other.assistantId) && Objects.equals(materialId, other.materialId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(assistantId, materialId);
        }
    }
}
