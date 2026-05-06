package com.skillforge.server.repository;

import com.skillforge.server.entity.EvalAnnotationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalAnnotationRepository extends JpaRepository<EvalAnnotationEntity, Long> {

    List<EvalAnnotationEntity> findByStatusOrderByCreatedAtDesc(String status);

    List<EvalAnnotationEntity> findAllByOrderByCreatedAtDesc();
}
