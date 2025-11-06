package com.aitravel.planner.repo;

import com.aitravel.planner.model.Day;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DayRepository extends JpaRepository<Day, UUID> {
    List<Day> findByPlanIdOrderByIndexAsc(UUID planId);
}