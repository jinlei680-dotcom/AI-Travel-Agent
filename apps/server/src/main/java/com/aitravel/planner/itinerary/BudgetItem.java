package com.aitravel.planner.itinerary;

import java.math.BigDecimal;

public class BudgetItem {
    private String name;
    private BigDecimal amount;
    private String currency;

    public BudgetItem() {}

    public BudgetItem(String name, BigDecimal amount, String currency) {
        this.name = name;
        this.amount = amount;
        this.currency = currency;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
