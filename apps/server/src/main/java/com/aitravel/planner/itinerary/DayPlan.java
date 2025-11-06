package com.aitravel.planner.itinerary;

import java.util.List;

public class DayPlan {
    private String summary;
    private List<Route> routes;
    private List<Poi> pois;

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<Route> getRoutes() { return routes; }
    public void setRoutes(List<Route> routes) { this.routes = routes; }

    public List<Poi> getPois() { return pois; }
    public void setPois(List<Poi> pois) { this.pois = pois; }
}