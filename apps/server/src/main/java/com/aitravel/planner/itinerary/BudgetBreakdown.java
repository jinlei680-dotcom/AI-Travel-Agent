package com.aitravel.planner.itinerary;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class BudgetBreakdown {
    private String currency = "CNY";
    private List<BudgetCategory> categories = new ArrayList<>();
    private BigDecimal grandTotal = BigDecimal.ZERO;
    private boolean aligned = false;

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public List<BudgetCategory> getCategories() { return categories; }
    public void setCategories(List<BudgetCategory> categories) { this.categories = categories; }

    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }

    public boolean isAligned() { return aligned; }
    public void setAligned(boolean aligned) { this.aligned = aligned; }
}
