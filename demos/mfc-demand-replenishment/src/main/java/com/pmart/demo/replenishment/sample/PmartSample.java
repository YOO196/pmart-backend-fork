package com.pmart.demo.replenishment.sample;

import com.pmart.demo.replenishment.csv.Csv;
import com.pmart.demo.replenishment.sales.Season;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * PMart 취급 상품(503 SKU)에 맞춘 합성 입력. 실제 판매가 아니다(시험 데이터 — 상품 목록·재고만 저장소의 LAN 시험 seed 에서 옴).
 *
 * <ul>
 *   <li>상품: {@code sample/pmart/catalog.csv}(← deploy/lan/seed/dimensions-estimated-2026-11-16.csv — SKU·이름·온도대·
 *       포장 형태)
 *   <li>판매: 명세 4장 일별 판매 형식, MFC-BJG-01, 2024-11-01 ~ 2026-11-23(첫 1년 뒤 1년을 순차 검증), Asia/Seoul, sales_basis ORDER_CONFIRMED. 센터는
 *       매일 영업(업무 정책 08:00~24:00). 포장 형태별 기본 수요 × 요일 × 계절 × 명절(설 2025-01-28~30·2026-02-16~18, 추석 2025-10-05~07·2026-09-24~26), 품절
 *       구간(STOCKOUT — 관측 판매는 수요보다 적다), 수집 누락일(MISSING), 기간 중 출시(PRE_LAUNCH)
 *   <li>시나리오(기준 2026-11-24 00:00): H·적격 상실 = {@code sample/pmart/seed-stock.csv}(← 1호점 고정본
 *       seed-template-2026-11-24.json, NORMAL·STORED 만, 소비기한 지난 LOT 제외). 약속·공급·상한·운송 배수·안전량은 데모 가정
 * </ul>
 *
 * 고정 시드라 매번 같다. 기본 출력 {@code build/pmart-sample/}.
 */
public final class PmartSample {

  public static final String MFC_ID = "MFC-BJG-01";
  public static final LocalDate FIRST = LocalDate.of(2024, 11, 1);
  public static final LocalDate LAST = LocalDate.of(2026, 11, 23);
  public static final LocalDateTime AS_OF = LocalDateTime.of(2026, 11, 24, 0, 0);
  static final Set<LocalDate> MISSING_DAYS =
      Set.of(LocalDate.of(2025, 6, 17), LocalDate.of(2026, 4, 10), LocalDate.of(2026, 8, 5));
  static final LocalDate[][] HOLIDAYS = {
    {LocalDate.of(2025, 1, 28), LocalDate.of(2025, 1, 30)}, // 설
    {LocalDate.of(2025, 10, 5), LocalDate.of(2025, 10, 7)}, // 추석
    {LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 18)}, // 설
    {LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 26)} // 추석
  };

  /** 포장 형태별 가정: 낱개 SKU 의 일 평균 수요 범위와 운송 배수 m. 묶음(B) SKU 는 수요 1/4, m 1. */
  record Rule(double minMean, double maxMean, int multiple) {}

  static final Map<String, Rule> RULES = new LinkedHashMap<>();

  static {
    RULES.put("produce", new Rule(6, 14, 6));
    RULES.put("meat_tray", new Rule(3, 9, 4));
    RULES.put("frozen_box", new Rule(1.5, 6, 6));
    RULES.put("box", new Rule(1, 5, 6));
    RULES.put("bottle", new Rule(2, 9, 12));
    RULES.put("can", new Rule(2, 8, 24));
    RULES.put("jar", new Rule(0.8, 3, 6));
    RULES.put("sack", new Rule(0.4, 2, 1));
    RULES.put("noodle", new Rule(2, 7, 4));
    RULES.put("snack", new Rule(2, 8, 10));
    RULES.put("tray", new Rule(2, 6, 6));
    RULES.put("pen", new Rule(0.1, 0.6, 10));
    RULES.put("eraser", new Rule(0.1, 0.4, 10));
    RULES.put("tissue", new Rule(0.5, 2, 1));
  }

  record Product(String sku, String name, String storage, String unitType, String rule) {
    Rule params() {
      return RULES.getOrDefault(rule, new Rule(1, 4, 6));
    }

    boolean bundle() {
      return "B".equals(unitType);
    }
  }

  /** SKU 별 고정 성질. */
  record Profile(Product p, double mean, int multiple, LocalDate launch) {}

  private PmartSample() {}

  public static void main(String[] args) throws IOException {
    Path root = Path.of(args.length > 0 ? args[0] : "sample/pmart");
    Path out = Path.of(args.length > 1 ? args[1] : "build/pmart-sample");
    Summary s = write(root, out);
    System.out.println("PMart 합성 입력: " + out.toAbsolutePath() + " — " + s);
  }

  /** 생성 요약. */
  public record Summary(int skus, long salesRows, long onHand, long expiredExcluded, long notStoredExcluded) {}

  public static Summary write(Path root, Path out) throws IOException {
    List<Product> products = new ArrayList<>();
    for (Csv.Row r : Csv.readAll(root.resolve("catalog.csv"))) {
      products.add(new Product(r.get("sku_id"), r.get("name"), r.get("storage_type"), r.get("unit_type"), r.get("package_rule")));
    }
    List<Profile> profiles = new ArrayList<>();
    for (Product p : products) {
      Random rnd = new Random(20261124L ^ p.sku().hashCode());
      Rule rule = p.params();
      double mean = rule.minMean() + rnd.nextDouble() * (rule.maxMean() - rule.minMean());
      if (p.bundle()) {
        mean /= 4;
      }
      LocalDate launch =
          rnd.nextDouble() < 0.04 ? LocalDate.of(2025, 3, 1).plusDays(rnd.nextInt(500)) : null;
      profiles.add(new Profile(p, mean, p.bundle() ? 1 : rule.multiple(), launch));
    }
    long rows = writeSales(profiles, out.resolve("sales.csv"));
    long[] stock = writeScenario(profiles, root.resolve("seed-stock.csv"), out.resolve("scenario"));
    return new Summary(profiles.size(), rows, stock[0], stock[1], stock[2]);
  }

  static double dowFactor(LocalDate d) {
    return switch (d.getDayOfWeek()) {
      case MONDAY, TUESDAY -> 0.9;
      case WEDNESDAY -> 0.95;
      case THURSDAY -> 1.0;
      case FRIDAY, SUNDAY -> 1.15;
      case SATURDAY -> 1.25;
    };
  }

  static double seasonFactor(Product p, LocalDate d) {
    Season s = Season.of(d);
    if ("FROZEN".equals(p.storage())) {
      return s == Season.SUMMER ? 1.35 : s == Season.WINTER ? 0.85 : 1.0;
    }
    if ("bottle".equals(p.rule()) || "can".equals(p.rule())) {
      return s == Season.SUMMER ? 1.25 : s == Season.WINTER ? 0.9 : 1.0;
    }
    if ("produce".equals(p.rule())) {
      return s == Season.AUTUMN ? 1.1 : 1.0;
    }
    return 1.0;
  }

  /** 명세 F03 선택 입력 "명절" 의 효과(모델은 아직 쓰지 않음): 연휴 5일 전부터 장보기 늘고, 연휴 중 줄어든다. */
  static double holidayFactor(Product p, LocalDate d) {
    for (LocalDate[] h : HOLIDAYS) {
      if (!d.isBefore(h[0]) && !d.isAfter(h[1])) {
        return 0.7;
      }
      if (!d.isBefore(h[0].minusDays(5)) && d.isBefore(h[0])) {
        boolean feast = "meat_tray".equals(p.rule()) || "produce".equals(p.rule()) || "frozen_box".equals(p.rule());
        return feast ? 1.5 : 1.1;
      }
    }
    return 1.0;
  }

  static long writeSales(List<Profile> profiles, Path file) throws IOException {
    long n = 0;
    try (Csv.Writer w =
        new Csv.Writer(
            file,
            "mfc_id",
            "sku_id",
            "business_date",
            "units_sold",
            "availability_status",
            "sales_basis",
            "source_timezone",
            "unknown_reason")) {
      for (Profile pr : profiles) {
        Random rnd = new Random(77L ^ pr.p().sku().hashCode());
        int stockoutLeft = 0;
        for (LocalDate d = FIRST; !d.isAfter(LAST); d = d.plusDays(1)) {
          double mean = pr.mean() * dowFactor(d) * seasonFactor(pr.p(), d) * holidayFactor(pr.p(), d);
          int demand = poisson(rnd, mean * erlang4(rnd));
          String status;
          long units;
          if (pr.launch() != null && d.isBefore(pr.launch())) {
            status = "PRE_LAUNCH";
            units = 0;
          } else if (MISSING_DAYS.contains(d)) {
            status = "MISSING";
            units = 0;
          } else {
            if (stockoutLeft == 0 && pr.mean() >= 1 && rnd.nextDouble() < 0.004) {
              stockoutLeft = 1 + rnd.nextInt(3);
            }
            if (stockoutLeft > 0) {
              stockoutLeft--;
              status = "STOCKOUT";
              units = (long) Math.floor(demand * rnd.nextDouble() * 0.4);
            } else {
              status = "NORMAL";
              units = demand;
            }
          }
          w.row(MFC_ID, pr.p().sku(), d, units, status, "ORDER_CONFIRMED", "Asia/Seoul", "");
          n++;
        }
      }
    }
    return n;
  }

  /** @return {H 합계, 소비기한 지나 뺀 양, STORED 아니라 뺀 양} */
  static long[] writeScenario(List<Profile> profiles, Path seedStock, Path dir) throws IOException {
    LocalDateTime ready = AS_OF.plusDays(1);
    LocalDateTime end = AS_OF.plusDays(3);
    Map<String, Long> onHand = new LinkedHashMap<>();
    Map<String, List<long[]>> expiries = new LinkedHashMap<>(); // sku → [qty, epochDay 상실일]
    long expired = 0;
    long notStored = 0;
    for (Csv.Row r : Csv.readAll(seedStock)) {
      long qty = Long.parseLong(r.get("qty"));
      if (!"NORMAL".equals(r.get("quality_state")) || !"STORED".equals(r.get("work_state"))) {
        notStored += qty;
        continue;
      }
      String exp = r.opt("expiry_date");
      LocalDate expiry = exp == null ? null : LocalDate.parse(exp);
      if (expiry != null && expiry.isBefore(AS_OF.toLocalDate())) {
        expired += qty; // 기준일에 이미 기한 지남 — 적격 현물 아님
        continue;
      }
      onHand.merge(r.get("sku_id"), qty, Long::sum);
      if (expiry != null && expiry.plusDays(1).atStartOfDay().isBefore(end)) {
        expiries.computeIfAbsent(r.get("sku_id"), k -> new ArrayList<>()).add(new long[] {qty, expiry.plusDays(1).toEpochDay()});
      }
    }
    Random rnd = new Random(20261124L);
    long hTotal = 0;
    try (Csv.Writer policy =
            new Csv.Writer(
                dir.resolve("policy.csv"),
                "mfc_id", "sku_id", "granularity", "protection_method", "review_interval_days",
                "transfer_multiple_m", "target_method", "target_quantile", "ss_qty",
                "calibrated_cumulative_quantile", "proposed_ready_at", "protection_end", "policy_version",
                "shared_cap_group", "accept_secured_supply");
        Csv.Writer caps =
            new Csv.Writer(
                dir.resolve("caps.csv"), "mfc_id", "sku_id", "cdc_available_qty", "mfc_receivable_qty",
                "transport_cap_qty");
        Csv.Writer inv = new Csv.Writer(dir.resolve("inventory.csv"), "mfc_id", "sku_id", "kind", "qty", "at");
        Csv.Writer com =
            new Csv.Writer(dir.resolve("commitments.csv"), "mfc_id", "sku_id", "order_line_id", "open_qty", "due_at", "status");
        Csv.Writer sup =
            new Csv.Writer(
                dir.resolve("supplies.csv"), "mfc_id", "sku_id", "supply_id", "stage", "remaining_qty",
                "ready_eta", "status")) {
      for (Profile pr : profiles) {
        String sku = pr.p().sku();
        int m = pr.multiple();
        long ss = (long) Math.ceil(pr.mean());
        policy.row(
            MFC_ID, sku, "DAY", "APPROX", 2, m, "POINT_PLUS_SS", "", ss, "", ready, end, "pmart-demo-1",
            "TRUCK-" + pr.p().storage(), "true");
        boolean scarce = rnd.nextDouble() < 0.05;
        long cdc = scarce ? rnd.nextInt(Math.max(1, m)) : (long) m * (10 + rnd.nextInt(30));
        caps.row(MFC_ID, sku, cdc, pr.p().bundle() ? 40 : 200, 500);
        long h = onHand.getOrDefault(sku, 0L);
        hTotal += h;
        inv.row(MFC_ID, sku, "H", h, AS_OF);
        for (long[] e : expiries.getOrDefault(sku, List.of())) {
          inv.row(MFC_ID, sku, "EXPIRY", e[0], LocalDate.ofEpochDay(e[1]).atStartOfDay());
        }
        if (rnd.nextDouble() < 0.15) {
          int lines = 1 + rnd.nextInt(3);
          for (int i = 1; i <= lines; i++) {
            com.row(MFC_ID, sku, "ORD-" + sku + "-" + i, 1 + rnd.nextInt(3), AS_OF.plusHours(9 + rnd.nextInt(14)), "OPEN");
          }
          if (rnd.nextDouble() < 0.1) {
            com.row(MFC_ID, sku, "ORD-" + sku + "-B", 1, AS_OF.plusHours(10), "BLOCKED");
          }
        }
        double s = rnd.nextDouble();
        if (s < 0.10) {
          sup.row(MFC_ID, sku, "TRF-" + sku + "-1", "DEPARTED", (long) m * (1 + rnd.nextInt(3)), AS_OF.plusHours(6), "OK");
        } else if (s < 0.15) {
          sup.row(MFC_ID, sku, "REQ-" + sku + "-1", "APPROVED", (long) m * 2, "", "OK");
        } else if (s < 0.18) {
          sup.row(MFC_ID, sku, "TRF-" + sku + "-2", "SECURED", (long) m * 2, ready.plusHours(6), "OK");
        } else if (s < 0.20) {
          sup.row(MFC_ID, sku, "TRF-" + sku + "-3", "DEPARTED", m, AS_OF.plusHours(6), "ETA_OR_QUALITY_UNKNOWN");
        }
      }
    }
    try (Csv.Writer shared = new Csv.Writer(dir.resolve("shared_caps.csv"), "group_id", "cap_qty")) {
      shared.row("TRUCK-FROZEN", 1200);
      shared.row("TRUCK-CHILLED", 1500);
      shared.row("TRUCK-AMBIENT", 3000);
    }
    return new long[] {hTotal, expired, notStored};
  }

  static int poisson(Random rnd, double mean) {
    if (mean <= 0) {
      return 0;
    }
    double l = Math.exp(-mean);
    int k = 0;
    double p = 1;
    do {
      k++;
      p *= rnd.nextDouble();
    } while (p > l);
    return k - 1;
  }

  /** 평균 1, 형태 4 감마(얼랑) — 날마다 수요 흔들림(과대산포). */
  static double erlang4(Random rnd) {
    double s = 0;
    for (int i = 0; i < 4; i++) {
      s -= Math.log(1 - rnd.nextDouble());
    }
    return s / 4;
  }
}
