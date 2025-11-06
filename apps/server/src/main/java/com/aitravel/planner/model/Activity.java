package com.aitravel.planner.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "activities")
public class Activity {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "day_id", nullable = false)
    private UUID dayId;

    @Column(nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "poi")
    private Map<String, Object> poi;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transport")
    private Map<String, Object> transport;

    @Column(name = "estimated_cost_amount")
    private BigDecimal estimatedCostAmount;

    @Column(name = "estimated_cost_currency")
    private String estimatedCostCurrency;

    @Column(name = "description")
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "images")
    private String[] images;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getDayId() { return dayId; }
    public void setDayId(UUID dayId) { this.dayId = dayId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Map<String, Object> getPoi() { return poi; }
    public void setPoi(Map<String, Object> poi) { this.poi = poi; }

    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }

    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }

    public Map<String, Object> getTransport() { return transport; }
    public void setTransport(Map<String, Object> transport) { this.transport = transport; }

    public BigDecimal getEstimatedCostAmount() { return estimatedCostAmount; }
    public void setEstimatedCostAmount(BigDecimal estimatedCostAmount) { this.estimatedCostAmount = estimatedCostAmount; }

    public String getEstimatedCostCurrency() { return estimatedCostCurrency; }
    public void setEstimatedCostCurrency(String estimatedCostCurrency) { this.estimatedCostCurrency = estimatedCostCurrency; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String[] getImages() { return images; }
    public void setImages(String[] images) { this.images = images; }
}