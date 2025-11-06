package com.aitravel.planner.repo;

import com.aitravel.planner.model.Activity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ActivityRepository extends JpaRepository<Activity, UUID> {
    List<Activity> findByDayId(UUID dayId);
}