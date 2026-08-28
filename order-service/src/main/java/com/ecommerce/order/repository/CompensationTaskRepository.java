package com.ecommerce.order.repository;

import com.ecommerce.order.entity.CompensationTask;
import com.ecommerce.order.entity.CompensationTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompensationTaskRepository extends JpaRepository<CompensationTask, Long> {
    List<CompensationTask> findTop50ByStatusOrderByIdAsc(CompensationTaskStatus status);

    List<CompensationTask> findByStatusOrderByIdAsc(CompensationTaskStatus status);
}
