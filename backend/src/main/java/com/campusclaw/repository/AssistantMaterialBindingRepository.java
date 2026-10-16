package com.campusclaw.repository;

import com.campusclaw.domain.AssistantMaterialBinding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssistantMaterialBindingRepository
        extends JpaRepository<AssistantMaterialBinding, AssistantMaterialBinding.MaterialKey> {

    List<AssistantMaterialBinding> findByAssistantId(Long assistantId);

    @Modifying
    @Query("delete from AssistantMaterialBinding b where b.assistantId = :assistantId")
    void deleteByAssistantId(@Param("assistantId") Long assistantId);
}
