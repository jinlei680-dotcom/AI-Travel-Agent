export type BudgetItem = { label: string; amount: number; currency: string; basis?: "per_person" | "total" };

const CNY_HINTS = ["元", "人民币", "CNY", "RMB", "￥", "¥"];
const USD_HINTS = ["USD", "美元", "$", "US$"];
const EUR_HINTS = ["EUR", "欧元", "€"];
const JPY_HINTS = ["JPY", "日元"]; // 不使用 "¥"，避免与人民币混淆

function detectCurrency(segment: string): string {
  const s = segment.toUpperCase();
  if (USD_HINTS.some(h => s.includes(h))) return "USD";
  if (EUR_HINTS.some(h => s.includes(h))) return "EUR";
  // 先判断 CNY，确保 "¥" 归属人民币
  if (CNY_HINTS.some(h => s.includes(h))) return "CNY";
  if (JPY_HINTS.some(h => s.includes(h))) return "JPY";
  return "CNY";
}

function toNumber(raw: string): number | null {
  try {
    let t = raw.trim().replace(/[,，]/g, "");
    // 如果文本包含“合计/总计/共/总额”等词，优先取其后面的数字
    const prefer = /(合计|总计|共|总额)[^0-9]{0,10}([0-9]+(?:\.[0-9]+)?)/i.exec(t);
    if (prefer) return parseFloat(prefer[2]);

    // 处理“1.2万”、“1万5”等中文表达
    const wanComplex = /([0-9]+(?:\.[0-9]+)?)\s*万\s*([0-9]+)?/.exec(t);
    if (wanComplex) {
      const base = parseFloat(wanComplex[1]) * 10000;
      const tail = wanComplex[2] ? parseFloat(wanComplex[2]) * 1000 : 0;
      return Math.round(base + tail);
    }
    const wanSimple = /([0-9]+(?:\.[0-9]+)?)\s*万/.exec(t);
    if (wanSimple) return Math.round(parseFloat(wanSimple[1]) * 10000);

    // 抽取全部数字，取最大值（避免选到“人均/单价”等较小数字）
    const all = t.match(/[0-9]+(?:\.[0-9]+)?/g);
    if (all && all.length) {
      const nums = all.map(parseFloat).filter(n => isFinite(n));
      if (nums.length) return Math.max(...nums);
    }

    // 若没有数字但出现“免费”，记为0
    if (/免费/.test(t)) return 0;
    return null;
  } catch { return null; }
}

export function parseBudgetItemsFromRaw(rawText: string): BudgetItem[] {
  const raw = (rawText || "").trim();
  if (!raw) return [];
  // 仅在“预算依据与汇总”段落中解析，避免误将描述性文本（如“休闲娱乐”）匹配为预算条目
  const anchorIdx = raw.search(/\*\*\s*预算依据与汇总\s*\*\*/);
  const section = anchorIdx >= 0 ? raw.slice(anchorIdx) : raw;
  const lines = section.split(/\n+/).map(l => l.trim()).filter(Boolean);

  const result: BudgetItem[] = [];
  const currencyDefault = detectCurrency(section);

  // 辅助：解析人数（如“3人”“3人同行”）用于门票等按人计算
  const partyMatch = raw.match(/([0-9]+)\s*人/);
  const partySize = partyMatch ? parseInt(partyMatch[1]) : null;

  for (const line of lines) {
    // 仅处理以破折或星标开头的预算条目行
    if (!/^[-*]/.test(line)) continue;
    if (line.includes("总预算")) continue; // 总预算单独处理
    // 分类：交通
    if (/^[-*]\s*交通/.test(line)) {
      const segs = line.split(/[；;]/).map(s => s.trim());
      let matchedSub = false;
      for (const s of segs) {
        if (/往返|机票|高铁/.test(s)) {
          const amt = toNumber(s);
          if (amt) result.push({ label: "交通（往返）", amount: amt, currency: detectCurrency(s) || currencyDefault, basis: detectBasis(s) });
          matchedSub = true;
        } else if (/市内交通/.test(s)) {
          const amt = toNumber(s);
          if (amt !== null && amt >= 0) result.push({ label: "市内交通", amount: amt, currency: detectCurrency(s) || currencyDefault, basis: detectBasis(s) });
          matchedSub = true;
        }
      }
      // 若未识别到细分条目，则作为整体“交通”类别汇总处理
      if (!matchedSub) {
        const amt = toNumber(line);
        if (amt !== null) result.push({ label: "交通", amount: amt, currency: detectCurrency(line) || currencyDefault, basis: detectBasis(line) });
      }
      continue;
    }
    // 分类：住宿
    if (/^[-*]\s*住宿/.test(line)) {
      const amt = toNumber(line);
      if (amt) result.push({ label: "住宿", amount: amt, currency: detectCurrency(line) || currencyDefault, basis: detectBasis(line) });
      continue;
    }
    // 分类：餐饮
    if (/^[-*]\s*餐饮/.test(line)) {
      const amt = toNumber(line);
      if (amt) result.push({ label: "餐饮", amount: amt, currency: detectCurrency(line) || currencyDefault, basis: detectBasis(line) });
      continue;
    }
    // 分类：门票（细项）
    if (/^[-*]\s*景点门票/.test(line)) {
      const parts = line.split(/[:：]/).slice(1).join(":");
      const entries = parts.split(/，/).map(s => s.trim());
      for (const e of entries) {
        if (!e) continue;
        if (/合计/.test(e)) continue; // 合计行单独由总预算或整类汇总处理
        if (/免费/.test(e)) {
          const name = e.replace(/免费.*/, "").trim();
          result.push({ label: name || "东湖绿道（免费）", amount: 0, currency: detectCurrency(e) || currencyDefault, basis: "total" });
          continue;
        }
        // 名称 + 单价（元/人）
        const m = e.match(/^([^0-9]+?)\s*([0-9]+(?:\.[0-9]+)?)\s*元(?:[\/／]?人|每人)?$/);
        if (m) {
          const name = m[1].trim().replace(/[0-9]+$/, "");
          const pricePer = parseFloat(m[2]);
          const total = partySize ? Math.round(pricePer * partySize) : pricePer;
          // 显示金额为总计（若识别到人数则乘以人数），来源为人均
          result.push({ label: name, amount: total, currency: detectCurrency(e) || currencyDefault, basis: "total" });
          continue;
        }
        // 回退：提取最大数字作为金额，名称去掉尾部数字
        const amt = toNumber(e);
        if (amt !== null) {
          const name = (e.replace(/([0-9]+(?:\.[0-9]+)?)\s*元.*/, "").trim() || e).replace(/[0-9]+$/, "");
          const total = partySize ? Math.round(amt * partySize) : amt;
          result.push({ label: name, amount: total, currency: detectCurrency(e) || currencyDefault, basis: detectBasis(e) });
        }
      }
      continue;
    }
    // 其他分类：购物/其他等（若存在数字则录入）
    const amt = toNumber(line);
    if (amt !== null) {
      // 尝试提取“标签：内容”形式的标签
      const label = (line.split(/[:：]/)[0] || "").replace(/^[-*]\s*/, "").trim();
      const finalLabel = label || "其他";
      result.push({ label: finalLabel, amount: amt, currency: detectCurrency(line) || currencyDefault, basis: detectBasis(line) });
    }
  }

  // 去重：同一标签保留金额较大的一个
  const map: Record<string, BudgetItem> = {};
  for (const it of result) {
    const prev = map[it.label];
    if (!prev || (it.amount || 0) > (prev.amount || 0)) map[it.label] = it;
  }
  return Object.values(map);
}

// 从原文解析总预算：
// 1) 优先匹配“总预算/预算总额/总体预算”；
// 2) 其次匹配我们生成的 Markdown 合计行“合计：xxx”；
// 3) 最后保守回退为行首“预算”，并排除“每日/人均/餐饮/门票/交通/住宿”等子类预算，避免误判每日人均金额。
export function parseTotalBudgetFromRaw(rawText: string): { amount: number | null; currency: string } {
  const raw = (rawText || "").trim();
  if (!raw) return { amount: null, currency: "CNY" };
  const lines = raw.split(/\n+/).map(l => l.trim()).filter(Boolean);

  // 1) 明确关键词优先：总预算/预算总额/总体预算（行首或列表项行首）
  for (const line of lines) {
    const m = line.match(/^(?:[-*]\s*)?(?:总预算|预算总额|总体预算)\s*[:：]?\s*([0-9]+(?:\.[0-9]+)?)\s*([A-Za-z￥¥RMB|CNY|USD|EUR|JPY]*)/i);
    if (m) {
      const amt = parseFloat(m[1]);
      const cur = detectCurrency(m[0]);
      return { amount: isFinite(amt) ? amt : null, currency: cur || "CNY" };
    }
  }

  // 2) Markdown 合计行
  const mTotal = raw.match(/合计\s*[:：]\s*([0-9]+(?:\.[0-9]+)?)\s*([A-Za-z￥¥RMB|CNY|USD|EUR|JPY]*)/i);
  if (mTotal) {
    const amt = parseFloat(mTotal[1]);
    const cur = detectCurrency(mTotal[0]);
    return { amount: isFinite(amt) ? amt : null, currency: cur || "CNY" };
  }

  // 3) 保守回退：仅匹配行首“预算”，排除子类预算语义
  for (const line of lines) {
    if (!/^预算/.test(line)) continue;
    if (/(每日|人均|餐饮|门票|交通|住宿)/.test(line)) continue;
    const m = line.match(/预算[^0-9]{0,8}([0-9]+(?:\.[0-9]+)?)\s*([A-Za-z￥¥RMB|CNY|USD|EUR|JPY]*)/i);
    if (m) {
      const amt = parseFloat(m[1]);
      const cur = detectCurrency(m[0]);
      return { amount: isFinite(amt) ? amt : null, currency: cur || "CNY" };
    }
  }

  return { amount: null, currency: "CNY" };
}

export function formatBudgetItemsMarkdown(items: BudgetItem[]): string {
  if (!items || items.length === 0) return "暂未解析到预算明细";
  const total = items
    .filter(it => !/（第[0-9]+天）/.test(it.label)) // 避免按天展开重复累加总额
    .reduce((s, it) => s + (it.amount || 0), 0);
  const curr = items[0]?.currency || "CNY";
  const lines = ["## 预算明细", "", ...items.map(it => `- ${it.label}：${Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(it.amount)} ${it.currency}`), "", `合计：${Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(total)} ${curr}`];
  return lines.join("\n");
}
export function parseChineseNum(n: string): number | null {
  const map: Record<string, number> = { 一:1, 二:2, 三:3, 四:4, 五:5, 六:6, 七:7, 八:8, 九:9, 十:10 };
  if (!n) return null;
  if (/^[0-9]+$/.test(n)) return parseInt(n, 10);
  if (n === "十") return 10;
  const chars = n.split("");
  // 简单处理 11-19：“十一”“十二”…
  if (chars.length === 2 && chars[0] === "十") {
    const u = map[chars[1]] || 0;
    return 10 + u;
  }
  // 20-90：“二十”“三十”…
  if (chars.length === 2 && chars[1] === "十") {
    const t = map[chars[0]] || 0;
    return t * 10;
  }
  return map[n] || null;
}

function getPartySize(rawText: string): number | null {
  const partyMatch = (rawText || "").match(/([0-9]+)\s*人/);
  return partyMatch ? parseInt(partyMatch[1]) : null;
}

export function parseDaysCountFromRaw(rawText: string): number | null {
  const raw = (rawText || "").trim();
  if (!raw) return null;
  const mTrip = raw.match(/([0-9一二三四五六七八九十]+)\s*日游/);
  if (mTrip && mTrip[1]) {
    const v = parseChineseNum(mTrip[1]);
    if (v && v > 0) return v;
  }
  // 根据“第X天”分段数量估算
  const markers = raw.match(/第\s*([0-9一二三四五六七八九十]+)\s*天/g);
  if (markers && markers.length) return markers.length;
  return null;
}

export function expandDiningByDay(items: BudgetItem[], rawText: string): BudgetItem[] {
  try {
    const list = Array.isArray(items) ? [...items] : [];
    const diningIdx = list.findIndex(it => it.label === "餐饮");
    const dining = diningIdx >= 0 ? list[diningIdx] : null;
    const currency = dining?.currency || detectCurrency(rawText);
    const days = parseDaysCountFromRaw(rawText) || null;
    const party = getPartySize(rawText) || null;

    // 提取“每日人均”单价
    const mRate = (rawText || "").match(/每日\s*人均\s*([0-9]+(?:\.[0-9]+)?)/);
    const rate = mRate ? parseFloat(mRate[1]) : null;

    // 仅当原文显式提供“每日人均”且有人数信息时，才按天拆分；
    // 否则保持“餐饮”作为合计项，不做按天展开。
    if (!rate || !party || !days || days <= 0) {
      return list;
    }

    const perDayTotal: number = Math.round(rate * party);
    if (!perDayTotal || perDayTotal <= 0) return list;

    const dailyItems: BudgetItem[] = [];
    for (let i = 1; i <= days; i++) {
      dailyItems.push({ label: `餐饮（第${i}天）`, amount: perDayTotal!, currency });
    }

    // 生成合计项（若原有餐饮合计存在则保留为“餐饮（合计）”）
    const merged: BudgetItem[] = list.filter(it => it.label !== "餐饮");
    if (dining?.amount) merged.push({ label: "餐饮（合计）", amount: dining.amount, currency });
    return [...merged, ...dailyItems];
  } catch {
    return items;
  }
}
  const detectBasis = (segment: string): "per_person" | "total" => (
    /人均|每人|元\s*\/\s*人|\/人|元人/.test(segment) ? "per_person" : "total"
  );
