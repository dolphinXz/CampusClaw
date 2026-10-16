package com.campusclaw.repository;

import com.campusclaw.domain.AssistantBinding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssistantBindingRepository extends JpaRepository<AssistantBinding, AssistantBinding.SkillKey> {

    List<AssistantBinding> findByAssistantId(Long assistantId);

    @Modifying
    @Query("delete from AssistantBinding b where b.assistantId = :assistantId")
    void deleteByAssistantId(@Param("assistantId") Long assistantId);
}
