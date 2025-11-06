package com.aitravel.planner.itinerary;

import java.util.List;

public class Poi {
    private String name;
    private List<Double> coord;
    private String type;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<Double> getCoord() { return coord; }
    public void setCoord(List<Double> coord) { this.coord = coord; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}