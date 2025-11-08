package com.aitravel.planner.itinerary;

import java.util.List;

public class ItineraryPlan {
    private List<Double> cityCenter;
    private List<DayPlan> days;
    private Budget baseBudget;

    public List<Double> getCityCenter() { return cityCenter; }
    public void setCityCenter(List<Double> cityCenter) { this.cityCenter = cityCenter; }

    public List<DayPlan> getDays() { return days; }
    public void setDays(List<DayPlan> days) { this.days = days; }

    public Budget getBaseBudget() { return baseBudget; }
    public void setBaseBudget(Budget baseBudget) { this.baseBudget = baseBudget; }
}
