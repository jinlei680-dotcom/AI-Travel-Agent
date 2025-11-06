package com.aitravel.planner.itinerary;

import java.util.List;

public class ItineraryPlan {
    private List<Double> cityCenter;
    private List<DayPlan> days;

    public List<Double> getCityCenter() { return cityCenter; }
    public void setCityCenter(List<Double> cityCenter) { this.cityCenter = cityCenter; }

    public List<DayPlan> getDays() { return days; }
    public void setDays(List<DayPlan> days) { this.days = days; }
}