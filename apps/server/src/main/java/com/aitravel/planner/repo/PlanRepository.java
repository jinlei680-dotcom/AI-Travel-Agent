package com.aitravel.planner.repo;

import com.aitravel.planner.model.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {
    List<Plan> findByOwnerId(UUID ownerId);
}