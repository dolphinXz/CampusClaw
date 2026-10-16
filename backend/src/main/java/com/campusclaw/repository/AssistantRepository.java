package com.campusclaw.repository;

import com.campusclaw.domain.Assistant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantRepository extends JpaRepository<Assistant, Long> {

    List<Assistant> findByClassIdOrderById(Long classId);

    List<Assistant> findByClassIdAndEnabledTrueOrderById(Long classId);

    Optional<Assistant> findByIdAndClassId(Long id, Long classId);

    boolean existsByClassIdAndName(Long classId, String name);

    boolean existsByClassIdAndNameAndIdNot(Long classId, String name, Long id);
}
