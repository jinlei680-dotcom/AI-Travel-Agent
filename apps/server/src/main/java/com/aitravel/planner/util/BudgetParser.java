package com.aitravel.planner.util;

import com.aitravel.planner.itinerary.BudgetBreakdown;
import com.aitravel.planner.itinerary.BudgetCategory;
import com.aitravel.planner.itinerary.BudgetItem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将规范化后的预算文本解析为结构化预算，并计算分项/类别/总计以保证金额一致性。
 */
public class BudgetParser {
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(CNY|RMB|人民币|元)?", Pattern.CASE_INSENSITIVE);

    public static BudgetBreakdown parse(String normalizedText) {
        BudgetBreakdown bd = new BudgetBreakdown();
        if (normalizedText == null || normalizedText.isBlank()) return bd;
        String[] lines = normalizedText.split("\\r?\\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("### 预算")) { start = i + 1; break; }
        }
        List<String> budgetLines = new ArrayList<>();
        if (start >= 0) {
            for (int i = start; i < lines.length; i++) {
                String t = lines[i].trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("#")) break;
                budgetLines.add(t.startsWith("- ") || t.startsWith("* ") ? t.substring(2).trim() : t);
            }
        }
        // 兜底：若未找到预算标题或段落中无列表项，则扫描全文中的列表项
        if (budgetLines.isEmpty()) {
            for (String ln : lines) {
                String t = ln.trim();
                if (t.startsWith("- ") || t.startsWith("* ")) {
                    budgetLines.add(t.substring(2).trim());
                }
            }
        }

        java.util.Map<BudgetCategory, BigDecimal> headerTotals = new java.util.HashMap<>();
        java.util.Map<String, BudgetCategory> byCategoryName = new java.util.HashMap<>();
        BudgetCategory current = null;
        for (String ln : budgetLines) {
            // 忽略总计/预算余量/合计等汇总行，避免误加入子项
            String low = ln.toLowerCase();
            if (low.startsWith("总花费") || low.contains("预算余量") || low.startsWith("合计")) {
                // 顶层总计留给校验阶段重算，不在此阶段解析
                continue;
            }
            // 解析“X小计：<金额> <币种>”行（例如：住宿小计/餐饮小计/交通小计/门票小计）
            // 注意：排除“类别小计”，该类型在后续专门处理
            if (ln.contains("小计") && !ln.startsWith("类别小计")) {
                String[] np0 = splitNameValue(ln.replace(" ", ""));
                String left0 = np0[0];
                String right0 = np0[1];
                String name0 = left0.replace("小计", "");
                String catKey0 = canonicalCategory(name0);
                if (catKey0 != null && !catKey0.isBlank()) {
                    BudgetCategory cat = byCategoryName.get(catKey0);
                    if (cat == null) {
                        cat = new BudgetCategory(catKey0, normalizeCurrency(right0));
                        byCategoryName.put(catKey0, cat);
                        bd.getCategories().add(cat);
                    }
                    BigDecimal amt0 = parseAmountPreferLast(ln);
                    if (amt0 != null && amt0.compareTo(BigDecimal.ZERO) > 0) {
                        headerTotals.put(cat, amt0);
                        cat.setTotal(amt0);
                        if (cat.getCurrency() == null || cat.getCurrency().isBlank()) cat.setCurrency(normalizeCurrency(ln));
                    }
                }
                continue;
            }
            // 解析“类别小计”行，例如：类别小计：住宿 2700 CNY，交通 140 CNY，餐饮 480 CNY，门票 130 CNY
            if (ln.startsWith("类别小计")) {
                for (String seg : ln.replace("类别小计：", "").split("，")) {
                    String s = seg.trim();
                    if (s.isEmpty()) continue;
                    String[] np = splitNameValue(s.replace(" ", ""));
                    String n = np[0];
                    BigDecimal amt = parseAmount(s);
                    String catKey = canonicalCategory(n);
                    if (catKey != null) {
                        BudgetCategory cat = byCategoryName.get(catKey);
                        if (cat == null) {
                            cat = new BudgetCategory(catKey, normalizeCurrency(s));
                            byCategoryName.put(catKey, cat);
                            bd.getCategories().add(cat);
                        }
                        if (amt != null && amt.compareTo(BigDecimal.ZERO) > 0) {
                            headerTotals.put(cat, amt);
                            cat.setTotal(amt);
                            if (cat.getCurrency() == null || cat.getCurrency().isBlank()) cat.setCurrency(normalizeCurrency(s));
                        }
                    }
                }
                continue;
            }

            String[] parts = splitNameValue(ln);
            String left = parts[0];
            String right = parts[1];

            if (isCategoryName(left)) {
                String catKey = canonicalCategory(left);
                String valueForParse = (right == null || right.isEmpty()) ? ln : right;
                current = byCategoryName.get(catKey);
                if (current == null) {
                    current = new BudgetCategory(catKey, normalizeCurrency(valueForParse));
                    byCategoryName.put(catKey, current);
                    bd.getCategories().add(current);
                }
                // 若类别行本身给出一个金额（如“住宿：2700 CNY”），记录为头部总额
                BigDecimal headerAmt = parseAmountPreferLast(valueForParse);
                if (headerAmt != null && headerAmt.compareTo(BigDecimal.ZERO) > 0) {
                    headerTotals.put(current, headerAmt);
                }
                // 若类别与条目写在同一行（例如："住宿：酒店A ... ≈ 2700 CNY"），同时解析为条目
                ItemParseResult itemFromSameLine = parseItemLine(ln);
                if (itemFromSameLine != null) {
                    current.getItems().add(new BudgetItem(itemFromSameLine.name, itemFromSameLine.amount, itemFromSameLine.currency));
                }
                continue;
            }

            // 解析条目行：优先使用“≈ 小计金额”，否则尝试单价×数量计算，最后回退到最后一个数字
            // 若一行包含多个条目（以中文分号“；”或英文分号“;”分隔），逐段解析
            String[] segments = ln.split("[；;]");
            for (String seg : segments) {
                String s = seg.trim(); if (s.isEmpty()) continue;
                ItemParseResult item = parseItemLine(s);
                if (item == null) continue;

                // 优先根据条目名推断类别；若当前类别为“其他”，则允许用条目名的分类覆盖
                String inferredKey = classifyCategoryFromItemName(item.name);
                BudgetCategory target = null;
                if (current != null && (inferredKey == null || !"其他".equals(current.getName()))) {
                    // 当前类别明确且不是“其他”，保持归属
                    target = current;
                } else if (inferredKey != null) {
                    // 当前为空或为“其他”，且条目名能推断具体类别，则按推断分类
                    target = byCategoryName.get(inferredKey);
                    if (target == null) {
                        target = new BudgetCategory(inferredKey, item.currency);
                        byCategoryName.put(inferredKey, target);
                        bd.getCategories().add(target);
                    }
                } else {
                    // 无法推断时，退回当前类别，若仍为空则归入“其他”
                    if (current != null) {
                        target = current;
                    } else {
                        String catKey = "其他";
                        target = byCategoryName.get(catKey);
                        if (target == null) {
                            target = new BudgetCategory(catKey, item.currency);
                            byCategoryName.put(catKey, target);
                            bd.getCategories().add(target);
                        }
                    }
                }

                target.getItems().add(new BudgetItem(item.name, item.amount, item.currency));
            }
        }

        BigDecimal grand = BigDecimal.ZERO;
        for (BudgetCategory cat : bd.getCategories()) {
            BigDecimal headerAmt = headerTotals.get(cat);
            if (headerAmt != null && headerAmt.compareTo(BigDecimal.ZERO) > 0) {
                // 若存在明确的小计/头部总额，则优先采用该值，并忽略子项金额的累加以避免双重累计
                cat.setTotal(headerAmt);
                if (cat.getCurrency() == null || cat.getCurrency().isBlank()) {
                    cat.setCurrency(bd.getCurrency() != null && !bd.getCurrency().isBlank() ? bd.getCurrency() : cat.getCurrency());
                }
                for (BudgetItem it : cat.getItems()) {
                    // 保留条目但不计入合计
                    it.setAmount(null);
                    if (cat.getCurrency() == null || cat.getCurrency().isBlank()) {
                        cat.setCurrency(it.getCurrency());
                    }
                }
            } else {
                // 无头部总额时，以子项金额累加为准
                BigDecimal sum = BigDecimal.ZERO;
                for (BudgetItem it : cat.getItems()) {
                    if (it.getAmount() != null) sum = sum.add(it.getAmount());
                    if (cat.getCurrency() == null || cat.getCurrency().isBlank()) {
                        cat.setCurrency(it.getCurrency());
                    }
                }
                cat.setTotal(sum);
            }
            if (bd.getCurrency() == null || bd.getCurrency().isBlank()) bd.setCurrency(cat.getCurrency());
            grand = grand.add(cat.getTotal() != null ? cat.getTotal() : BigDecimal.ZERO);
        }
        bd.setGrandTotal(grand);
        // 解析阶段不武断宣称对齐；后续由 BudgetVerifier 统一校验与修正
        bd.setAligned(true);
        return bd;
    }

    private static String[] splitNameValue(String ln) {
        if (ln == null) return new String[]{"", ""};
        String tmp = ln;
        int asciiIdx = tmp.indexOf(":");
        int fullIdx = tmp.indexOf("\uFF1A");
        int idx = -1;
        if (asciiIdx >= 0 && fullIdx >= 0) idx = Math.min(asciiIdx, fullIdx);
        else if (asciiIdx >= 0) idx = asciiIdx;
        else if (fullIdx >= 0) idx = fullIdx;
        if (idx >= 0) {
            String left = tmp.substring(0, idx).trim();
            String right = tmp.substring(idx + 1).trim();
            return new String[]{left, right};
        }
        return new String[]{tmp.trim(), ""};
    }

    private static boolean isCategoryName(String name) {
        String n = name == null ? "" : name.trim().toLowerCase();
        // 允许类别后跟括号或额外描述，例如："住宿（3晚）"、"餐饮 - 每天" 等
        if (n.startsWith("住宿") || n.startsWith("餐饮") || n.startsWith("交通") || n.startsWith("门票")) return true;
        if (n.startsWith("lodging") || n.startsWith("dining") || n.startsWith("transport") || n.startsWith("tickets")) return true;
        // 兼容“其他费用/其他”作为类别
        if (n.startsWith("其他") || n.startsWith("其他费用") || n.startsWith("other")) return true;
        return false;
    }

    private static String canonicalCategory(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase();
        // 排除“类别”作为真实类别，防止“类别小计”误解析
        if (n.startsWith("类别")) return null;
        if (n.startsWith("住宿") || n.startsWith("lodging")) return "住宿";
        if (n.startsWith("餐饮") || n.startsWith("dining")) return "餐饮";
        if (n.startsWith("交通") || n.startsWith("transport")) return "交通";
        if (n.startsWith("门票") || n.startsWith("tickets")) return "门票";
        if (n.startsWith("其他") || n.startsWith("其他费用") || n.startsWith("other")) return "其他";
        return name.trim();
    }

    private static BigDecimal parseAmount(String text) {
        if (text == null) return BigDecimal.ZERO;
        String cleaned = text.replace(",", "");
        Matcher m = AMOUNT_PATTERN.matcher(cleaned);
        if (m.find()) {
            try { return new BigDecimal(m.group(1)); } catch (Exception ignored) {}
        }
        // 兜底：移除非数字和点的字符，尝试解析（适配“约”“（）”等）
        String digitsOnly = cleaned.replaceAll("[^0-9\\.]", "");
        if (digitsOnly.matches("[0-9]+(?:\\.[0-9]+)?")) {
            try { return new BigDecimal(digitsOnly); } catch (Exception ignored) {}
        }
        return BigDecimal.ZERO;
    }

    // 优先解析行中最后一个金额（适配“≈ 小计金额”场景）
    private static BigDecimal parseAmountPreferLast(String text) {
        if (text == null) return BigDecimal.ZERO;
        String cleaned = text.replace(",", "");
        Matcher m = AMOUNT_PATTERN.matcher(cleaned);
        BigDecimal last = null;
        while (m.find()) {
            try { last = new BigDecimal(m.group(1)); } catch (Exception ignored) {}
        }
        if (last != null) return last;
        return parseAmount(text);
    }

    private static class ItemParseResult {
        final String name; final BigDecimal amount; final String currency;
        ItemParseResult(String n, BigDecimal a, String c) { name = n; amount = a; currency = c; }
    }

    private static ItemParseResult parseItemLine(String ln) {
        if (ln == null || ln.isBlank()) return null;
        String t = ln.trim();
        // 直接使用“≈ 小计金额”或“= 小计金额”
        int approxIdx = Math.max(t.lastIndexOf("≈"), t.lastIndexOf("="));
        if (approxIdx > 0) {
            String left = t.substring(0, approxIdx).trim();
            String right = t.substring(approxIdx + 1).trim();
            BigDecimal amt = parseAmountPreferLast(right);
            String cur = normalizeCurrency(right);
            String[] np = splitNameValue(left);
            String name = np[0];
            // 若左侧包含类别名与条目名（例如："住宿：酒店A"），则以条目名作为 name
            if (isCategoryName(name) && np[1] != null && !np[1].isBlank()) {
                name = np[1];
            }
            if (name == null || name.isBlank()) name = t; // 兜底
            return new ItemParseResult(name, amt, cur);
        }
        // 尝试单价×数量（若无≈/=/小计）
        java.util.List<BigDecimal> nums = new java.util.ArrayList<>();
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(t);
        while (m.find()) {
            try { nums.add(new BigDecimal(m.group(1))); } catch (Exception ignored) {}
        }
        BigDecimal amt = null;
        if (nums.size() >= 2) {
            amt = nums.get(0).multiply(nums.get(1));
        } else if (nums.size() == 1) {
            amt = nums.get(0);
        }
        if (amt == null) return null;
        String cur = normalizeCurrency(t);
        String[] np = splitNameValue(t);
        String name = np[0];
        if (name == null || name.isBlank()) name = t;
        return new ItemParseResult(name, amt, cur);
    }

    private static String classifyCategoryFromItemName(String name) {
        if (name == null) return null;
        String n = name.trim();
        // 住宿：酒店/民宿/宾馆/旅店/客栈/青旅
        if (n.matches(".*(酒店|民宿|宾馆|旅店|客栈|青旅|inn|hostel|hotel).*")) return "住宿";
        // 门票/票务
        if (n.matches(".*(门票|票|入场券|ticket).*")) return "门票";
        // 交通：打车/地铁/公交/出租车
        if (n.matches(".*(打车|出租车|地铁|公交|车费|滴滴|taxi).*")) return "交通";
        // 餐饮：餐厅/饭店/菜馆/小吃/人均/顿
        if (n.matches(".*(餐厅|餐馆|饭店|菜馆|小吃|美食|人均|顿|早餐|午餐|晚餐).*")) return "餐饮";
        return null;
    }

    private static String normalizeCurrency(String text) {
        if (text == null) return "CNY";
        String t = text.toUpperCase();
        if (t.contains("CNY") || t.contains("RMB") || t.contains("人民币") || t.contains("元")) return "CNY";
        if (t.contains("USD") || t.contains("美元")) return "USD";
        if (t.contains("EUR") || t.contains("欧元")) return "EUR";
        return "CNY";
    }
}
