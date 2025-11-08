package com.aitravel.planner.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级原文(rawText)预算规范化：
 * - 若检测到预算明细但没有“### 预算”标题，则插入标题；
 * - 排除包含“每日/每天/人均/每人/按天”等字样的总预算误判；
 * - 若无有效总预算行且各项明细币种一致，自动汇总为“合计：<sum> <currency>”；
 * - 尽量保持原文不变，只做轻量插入或追加，避免破坏模型文本自然性。
 */
public class BudgetRawTextNormalizer {
    // 类别行：- 住宿：1200 CNY 等
    private static final Pattern ITEM_LINE = Pattern.compile(
            "(?m)^[\\-\\*]\\s*(住宿|酒店|交通|餐饮|吃饭|用餐|门票|景点|购物|其他)[：:]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(CNY|RMB|人民币|元|USD|EUR|JPY)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    // 类别汇总行（允许“（总计）”）
    private static final Pattern CATEGORY_HEADER = Pattern.compile(
            "(?m)^[\\-\\*]\\s*(住宿|交通|餐饮|门票)[：:]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(CNY|RMB|人民币|元|USD|EUR|JPY)?(?:\\s*（总计）)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    // 类别子项行：以缩进破折号开头，并在行尾包含“= 合计金额 币种”
    private static final Pattern CATEGORY_ITEM_TOTAL = Pattern.compile(
            "(?m)^\\s+[\\-\\*]\\s*.*?=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(CNY|RMB|人民币|元|USD|EUR|JPY)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    // 总预算/合计行（排除含每日/人均等词的情况）
    private static final Pattern TOTAL_LINE = Pattern.compile(
            "(?m)^(?:总预算|预算总额|总体预算|合计)[：:]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(CNY|RMB|人民币|元|USD|EUR|JPY)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_LINE_WITH_DISALLOWED = Pattern.compile(
            "(?m)^(?:总预算|预算总额|总体预算|合计)[：:].*(每日|每天|人均|每人|按天).*$",
            Pattern.CASE_INSENSITIVE);

    public static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) return raw;
        String text = raw;

        // 收集预算明细项
        Matcher m = ITEM_LINE.matcher(text);
        List<BigDecimal> amounts = new ArrayList<>();
        String currency = null;
        while (m.find()) {
            try {
                BigDecimal amt = new BigDecimal(m.group(2));
                amounts.add(amt);
                String cur = normalizeCurrency(m.group(3), text);
                if (currency == null) currency = cur;
                else if (!currency.equals(cur)) currency = null; // 并币种不一致则放弃自动合计
            } catch (Exception ignored) {}
        }

        boolean hasItems = !amounts.isEmpty();
        boolean hasBudgetHeading = text.contains("### 预算") || Pattern.compile("(?m)^[#]{1,6}\\s*预算\\s*$").matcher(text).find();

        // 处理总预算行：必须存在且不含禁用词
        boolean hasTotalValid = false;
        Matcher mt = TOTAL_LINE.matcher(text);
        while (mt.find()) {
            int start = mt.start();
            int end = mt.end();
            String line = text.substring(start, end);
            if (!TOTAL_LINE_WITH_DISALLOWED.matcher(line).find()) {
                hasTotalValid = true;
                break;
            }
        }

        StringBuilder out = new StringBuilder(text);

        // 若检测到预算项但没有预算标题，插入标题到第一条预算项之前
        if (hasItems && !hasBudgetHeading) {
            Matcher firstItem = ITEM_LINE.matcher(out);
            if (firstItem.find()) {
                int insertPos = firstItem.start();
                out.insert(insertPos, "\n### 预算\n");
            } else {
                out.insert(0, "### 预算\n");
            }
        }

        // 若无有效总预算行，且币种一致、至少两项，自动汇总追加“合计”
        if (!hasTotalValid && currency != null && amounts.size() >= 2) {
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal a : amounts) sum = sum.add(a);
            String cur = currency;
            // 将“合计”追加到预算段落末尾（若存在），否则追加到文本末尾
            int budgetSectionEnd = findBudgetSectionEnd(out.toString());
            String appendLine = String.format(Locale.ROOT, "合计：%s %s", stripTrailingZeros(sum), cur);
            if (budgetSectionEnd >= 0) {
                out.insert(budgetSectionEnd, appendLine + "\n");
            } else {
                out.append("\n").append(appendLine).append("\n");
            }
        }

        // 强一致性修正：
        // 1) 逐类扫描，按子项“= 合计金额”累加，校正类别汇总数值；
        // 2) 计算四类总额，若存在“总预算校验/合计”，则以计算值更新；否则在预算段落末尾追加“合计”。
        out = new StringBuilder(enforceConsistency(out.toString()));
        return out.toString();
    }

    private static String normalizeCurrency(String curRaw, String fullText) {
        String c = curRaw == null ? "" : curRaw.trim().toUpperCase(Locale.ROOT);
        if (c.isEmpty()) {
            String t = fullText == null ? "" : fullText.toUpperCase(Locale.ROOT);
            if (t.contains("人民币") || t.contains("元") || t.contains("RMB") || t.contains("CNY")) return "CNY";
            return "CNY";
        }
        if (c.equals("人民币") || c.equals("元") || c.equals("RMB") || c.equals("CNY")) return "CNY";
        return c;
    }

    private static int findBudgetSectionEnd(String text) {
        // 简单策略：找到预算标题后，定位到紧随其后的列表块末尾
        int headingIdx = text.indexOf("### 预算");
        if (headingIdx < 0) return -1;
        int pos = headingIdx + "### 预算".length();
        // 遍历直到遇到非列表行或文本末尾
        Matcher mm = Pattern.compile("(?m)^[\\-\\*]\\s+.*$").matcher(text);
        int lastListEnd = -1;
        while (mm.find()) {
            if (mm.start() > headingIdx) {
                lastListEnd = mm.end();
            }
        }
        return lastListEnd;
    }

    private static String enforceConsistency(String text) {
        String[] lines = text.split("\n", -1);
        BigDecimal totalAll = BigDecimal.ZERO;
        String currencyAll = null;
        // 记录每类的计算合计，便于总预算校验
        BigDecimal lodgingSum = BigDecimal.ZERO, transportSum = BigDecimal.ZERO, foodSum = BigDecimal.ZERO, ticketSum = BigDecimal.ZERO;

        for (int i = 0; i < lines.length; i++) {
            Matcher head = CATEGORY_HEADER.matcher(lines[i]);
            if (!head.find()) continue;
            String category = head.group(1);
            String headerCurrency = normalizeCurrency(head.group(3), text);
            if (currencyAll == null) currencyAll = headerCurrency;
            BigDecimal sum = BigDecimal.ZERO;
            List<String> itemTotalsStr = new ArrayList<>();
            // 收集该类别子项（连续缩进列表）
            int j = i + 1;
            while (j < lines.length) {
                String ln = lines[j];
                // 下一个类别或非缩进行则停止
                if (CATEGORY_HEADER.matcher(ln).find()) break;
                if (!ln.matches("^\\s+[\\-\\*].*$")) { j++; continue; }
                Matcher it = CATEGORY_ITEM_TOTAL.matcher(ln);
                if (it.find()) {
                    try {
                        BigDecimal t = new BigDecimal(it.group(1));
                        sum = sum.add(t);
                        itemTotalsStr.add(stripTrailingZeros(t));
                        // 同步币种
                        String cur = normalizeCurrency(it.group(2), text);
                        if (cur != null && !cur.isEmpty()) headerCurrency = cur;
                    } catch (Exception ignored) {}
                } else if (ln.contains("免费")) {
                    itemTotalsStr.add("0");
                }
                j++;
            }
            // 用计算值校正类别汇总行
            String fixed = String.format(Locale.ROOT, "- %s：%s %s（总计）", category, stripTrailingZeros(sum), headerCurrency);
            lines[i] = fixed;
            // 累加到总额
            totalAll = totalAll.add(sum);
            // 分类记录
            switch (category) {
                case "住宿": lodgingSum = sum; break;
                case "交通": transportSum = sum; break;
                case "餐饮": foodSum = sum; break;
                case "门票": ticketSum = sum; break;
                default: break;
            }
            // 在该类别块范围内查找并更新“校验：”行（允许缩进与列表前缀）
            for (int k = i + 1; k < j; k++) {
                String chkRaw = lines[k] == null ? "" : lines[k];
                String chkTrim = chkRaw.trim();
                if (chkTrim.startsWith("校验：") || chkTrim.startsWith("- 校验：") || chkTrim.startsWith("* 校验：")) {
                    String prefix = chkRaw.substring(0, chkRaw.indexOf(chkTrim));
                    String eq = String.format(Locale.ROOT, "校验：%s %s = %s CNY", stripTrailingZeros(sum), headerCurrency,
                            String.join(" + ", itemTotalsStr));
                    // 保持原有是否使用列表前缀
                    if (chkTrim.startsWith("- 校验：")) eq = "- " + eq;
                    if (chkTrim.startsWith("* 校验：")) eq = "* " + eq;
                    lines[k] = prefix + eq;
                    break;
                }
            }
        }

        // 更新“总预算校验”或“合计”
        String totalStr = stripTrailingZeros(totalAll);
        String totalLine = String.format(Locale.ROOT, "合计：%s %s", totalStr, currencyAll == null ? "CNY" : currencyAll);
        boolean hasTotalLine = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i] == null ? "" : lines[i].trim();
            if (trimmed.startsWith("总预算校验：")) {
                String prefix = lines[i].startsWith("-") || lines[i].startsWith("*") ? lines[i].substring(0, lines[i].indexOf(trimmed)) : "";
                lines[i] = prefix + String.format(Locale.ROOT, "总预算校验：%s %s = 住宿 + 交通 + 餐饮 + 门票 = %s %s",
                        totalStr, currencyAll == null ? "CNY" : currencyAll, totalStr, currencyAll == null ? "CNY" : currencyAll);
                hasTotalLine = true;
                break;
            }
            // 匹配可能带有列表前缀的“总预算/合计”行
            Matcher mt = Pattern.compile("(?m)^(?:[\\-\\*]\\s*)?(?:总预算|预算总额|总体预算|合计)[：:]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(CNY|RMB|人民币|元|USD|EUR|JPY)?\\s*$",
                    Pattern.CASE_INSENSITIVE).matcher(lines[i]);
            if (mt.find()) {
                String prefix = lines[i].startsWith("-") || lines[i].startsWith("*") ? lines[i].substring(0, lines[i].indexOf(lines[i].trim())) : "";
                lines[i] = prefix + totalLine;
                hasTotalLine = true;
                break;
            }
        }
        if (!hasTotalLine) {
            // 追加到文本末尾
            String[] appended = new String[lines.length + 1];
            System.arraycopy(lines, 0, appended, 0, lines.length);
            appended[lines.length] = totalLine;
            lines = appended;
        }

        return String.join("\n", lines);
    }

    private static String stripTrailingZeros(BigDecimal bd) {
        try {
            return bd.stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return bd.toPlainString();
        }
    }
}
