package com.campusclaw.repository;

import com.campusclaw.domain.KnowledgeEntry;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, Long> {

    /** 按材料与班级取正文条目（班级条件显式携带，防止越权取到他班正文）。 */
    Optional<KnowledgeEntry> findByMaterialIdAndClassId(Long materialId, Long classId);

    boolean existsByMaterialIdAndClassId(Long materialId, Long classId);
}
