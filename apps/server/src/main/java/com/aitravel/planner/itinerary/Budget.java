package com.aitravel.planner.itinerary;

import java.math.BigDecimal;

public class Budget {
    private BigDecimal amount;
    private String currency;

    public Budget() {}

    public Budget(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}

