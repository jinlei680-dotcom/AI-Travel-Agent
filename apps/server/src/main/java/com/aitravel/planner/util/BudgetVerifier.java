package com.aitravel.planner.util;

import com.aitravel.planner.itinerary.BudgetBreakdown;
import com.aitravel.planner.itinerary.BudgetCategory;
import com.aitravel.planner.itinerary.BudgetItem;

import java.math.BigDecimal;

/**
 * 对结构化预算做一致性校验与纠正：
 * - 若类别下子项合计与类别总额不一致，以子项合计为准并标记未对齐；
 * - 统一类别币种（优先第一个有币种的子项）；
 * - 重算 grandTotal 与顶层币种；
 */
public class BudgetVerifier {
    public static BudgetBreakdown fixAndAlign(BudgetBreakdown bd) {
        if (bd == null) return null;
        boolean aligned = true;
        BigDecimal grand = BigDecimal.ZERO;
        String topCurrency = bd.getCurrency();

        if (bd.getCategories() != null) {
            // 按需求：移除“其他”类别，不参与展示与合计
            bd.getCategories().removeIf(cat -> {
                String name = cat.getName();
                return name != null && name.trim().equals("其他");
            });

            for (BudgetCategory cat : bd.getCategories()) {
                BigDecimal sum = BigDecimal.ZERO;
                String catCurrency = cat.getCurrency();

                if (cat.getItems() != null) {
                    for (BudgetItem it : cat.getItems()) {
                        if (it.getAmount() != null) {
                            sum = sum.add(it.getAmount());
                        }
                        // 统一币种：以第一个子项币种作为类别币种（若类别为空）
                        if ((catCurrency == null || catCurrency.isBlank()) && it.getCurrency() != null && !it.getCurrency().isBlank()) {
                            catCurrency = it.getCurrency();
                        }
                    }
                }

                if (catCurrency != null && !catCurrency.isBlank()) {
                    cat.setCurrency(catCurrency);
                }

                // 若子项合计>0且与类别 total 不一致，则以子项合计为准
                if (sum.compareTo(BigDecimal.ZERO) > 0) {
                    if (cat.getTotal() == null || sum.compareTo(cat.getTotal()) != 0) {
                        aligned = false;
                        cat.setTotal(sum);
                    }
                } else {
                    // 若类别 total 为 0 或空，但存在头部总额（已在解析阶段设置），保持不变；否则设为 0
                    if (cat.getTotal() == null) {
                        cat.setTotal(BigDecimal.ZERO);
                    }
                }

                // 累加 grand total
                if (cat.getTotal() != null) {
                    grand = grand.add(cat.getTotal());
                }

                // 继承顶层币种
                if ((topCurrency == null || topCurrency.isBlank()) && cat.getCurrency() != null && !cat.getCurrency().isBlank()) {
                    topCurrency = cat.getCurrency();
                }
            }
        }

        // 若重算后的 grand 与原值不一致，则以重算值为准并标记未对齐
        if (bd.getGrandTotal() == null || grand.compareTo(bd.getGrandTotal()) != 0) {
            aligned = false;
        }
        bd.setGrandTotal(grand);
        if (topCurrency != null && !topCurrency.isBlank()) {
            bd.setCurrency(topCurrency);
        }
        bd.setAligned(aligned);
        return bd;
    }
}