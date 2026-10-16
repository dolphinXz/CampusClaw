package com.campusclaw.repository;

import com.campusclaw.domain.SystemPrompt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemPromptRepository extends JpaRepository<SystemPrompt, Long> {

    Optional<SystemPrompt> findByClassId(Long classId);
}
