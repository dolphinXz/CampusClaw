package com.campusclaw.repository;

import com.campusclaw.domain.AiSkill;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSkillRepository extends JpaRepository<AiSkill, Long> {

    List<AiSkill> findByClassIdOrderById(Long classId);

    List<AiSkill> findByClassIdAndEnabledTrueOrderById(Long classId);

    Optional<AiSkill> findByClassIdAndCode(Long classId, String code);

    Optional<AiSkill> findByIdAndClassId(Long id, Long classId);

    boolean existsByClassIdAndName(Long classId, String name);

    boolean existsByClassIdAndNameAndIdNot(Long classId, String name, Long id);

    /** 级联解绑由 FK ON DELETE CASCADE 保证，此处提供显式删除入口。 */
    void deleteByIdAndClassId(Long id, Long classId);
}
