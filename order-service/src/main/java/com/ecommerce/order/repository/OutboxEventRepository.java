package com.ecommerce.order.repository;

import com.ecommerce.order.entity.OutboxEvent;
import com.ecommerce.order.entity.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop50ByStatusOrderByIdAsc(OutboxEventStatus status);
}
