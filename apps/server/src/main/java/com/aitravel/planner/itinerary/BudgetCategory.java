package com.aitravel.planner.itinerary;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class BudgetCategory {
    private String name;
    private List<BudgetItem> items = new ArrayList<>();
    private BigDecimal total = BigDecimal.ZERO;
    private String currency;

    public BudgetCategory() {}

    public BudgetCategory(String name, String currency) {
        this.name = name;
        this.currency = currency;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<BudgetItem> getItems() { return items; }
    public void setItems(List<BudgetItem> items) { this.items = items; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
