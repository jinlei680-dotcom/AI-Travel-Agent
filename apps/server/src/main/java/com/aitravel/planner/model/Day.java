package com.aitravel.planner.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "days")
public class Day {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "index", nullable = false)
    private int index;

    @Column(nullable = false)
    private LocalDate date;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "accommodation")
    private Map<String, Object> accommodation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transport")
    private Map<String, Object> transport;

    @Column(name = "summary")
    private String summary;

    @Column(name = "notes")
    private String notes;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public Map<String, Object> getAccommodation() { return accommodation; }
    public void setAccommodation(Map<String, Object> accommodation) { this.accommodation = accommodation; }

    public Map<String, Object> getTransport() { return transport; }
    public void setTransport(Map<String, Object> transport) { this.transport = transport; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}