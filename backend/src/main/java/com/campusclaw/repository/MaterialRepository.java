package com.campusclaw.repository;

import com.campusclaw.domain.Material;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    List<Material> findByClassRoomIdOrderByIdDesc(Long classRoomId);
}
