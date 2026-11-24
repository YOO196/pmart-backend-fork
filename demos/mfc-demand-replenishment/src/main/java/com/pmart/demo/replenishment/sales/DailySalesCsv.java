package com.pmart.demo.replenishment.sales;

import com.pmart.demo.replenishment.csv.Csv;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 명세 4장 "일별 판매" 형식 CSV 읽기: mfc_id, sku_id, business_date, units_sold, availability_status, sales_basis,
 * source_timezone, (unknown_reason). 상태는 원천이 준 그대로 쓴다. 한 파일 = 한 센터·한 집계 기준·한 시간대(섞이면 거절 — 기준이 다른
 * 숫자를 섞지 않는다). 중복 일별 키·음수·모르는 상태는 오류로 세고 그 줄은 버린다(명세 F02).
 */
public final class DailySalesCsv {

  public static final String HEADER_MARK = "availability_status";

  /** 읽은 결과와 오류 건수. */
  public record Result(SalesSource source, long readLines, long keptLines, Map<String, Long> errors) {}

  private DailySalesCsv() {}

  /** 머리줄에 availability_status 가 있으면 이 형식. */
  public static boolean matches(Path csv) throws IOException {
    try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
      String header = in.readLine();
      return header != null && header.contains(HEADER_MARK);
    }
  }

  public static Result read(Path csv) throws IOException {
    Map<String, NavigableMap<LocalDate, DailySale>> days = new TreeMap<>();
    Map<String, NavigableMap<LocalDate, Long>> units = new TreeMap<>();
    TreeSet<LocalDate> center = new TreeSet<>();
    Map<String, Long> errors = new TreeMap<>();
    String[] meta = new String[3]; // mfc, basis, tz
    LocalDate[] range = new LocalDate[2];
    long[] counts = new long[2];
    Csv.read(
        csv,
        row -> {
          counts[0]++;
          DailySale s;
          try {
            AvailabilityStatus status = AvailabilityStatus.valueOf(row.get("availability_status").trim());
            String reason = row.opt("unknown_reason");
            s =
                new DailySale(
                    row.get("mfc_id").trim(),
                    row.get("sku_id").trim(),
                    LocalDate.parse(row.get("business_date").trim()),
                    Long.parseLong(row.get("units_sold").trim()),
                    status,
                    status == AvailabilityStatus.UNKNOWN
                        ? UnknownReason.valueOf(reason == null ? "SOURCE_UNAVAILABLE" : reason)
                        : null,
                    row.get("sales_basis").trim(),
                    row.get("source_timezone").trim());
          } catch (RuntimeException e) {
            errors.merge("INVALID_ROW", 1L, Long::sum);
            return;
          }
          String[] got = {s.mfcId(), s.salesBasis(), s.sourceTimezone()};
          for (int i = 0; i < 3; i++) {
            if (meta[i] == null) {
              meta[i] = got[i];
            } else if (!meta[i].equals(got[i])) {
              throw new IllegalArgumentException(
                  "한 파일에 센터·집계 기준·시간대가 섞임: " + meta[i] + " / " + got[i] + " (줄 " + row.lineNo() + ")");
            }
          }
          NavigableMap<LocalDate, DailySale> skuDays = days.computeIfAbsent(s.skuId(), k -> new TreeMap<>());
          if (skuDays.containsKey(s.businessDate())) {
            errors.merge("DUPLICATE_DAILY_KEY", 1L, Long::sum);
            return;
          }
          skuDays.put(s.businessDate(), s);
          counts[1]++;
          range[0] = range[0] == null || s.businessDate().isBefore(range[0]) ? s.businessDate() : range[0];
          range[1] = range[1] == null || s.businessDate().isAfter(range[1]) ? s.businessDate() : range[1];
          if (s.unitsSold() > 0) {
            units.computeIfAbsent(s.skuId(), k -> new TreeMap<>()).put(s.businessDate(), s.unitsSold());
            center.add(s.businessDate());
          }
        });
    if (range[0] == null) {
      throw new IllegalArgumentException("읽을 수 있는 줄이 없음: " + csv);
    }
    for (String sku : days.keySet()) {
      units.computeIfAbsent(sku, k -> new TreeMap<>());
    }
    SalesSource source = new SalesSource(meta[0], meta[1], meta[2], range[0], range[1], units, center, days);
    return new Result(source, counts[0], counts[1], errors);
  }

  /** 상태별 줄 수(보고용). */
  public static Map<AvailabilityStatus, Long> statusCounts(SalesSource source) {
    Map<AvailabilityStatus, Long> out = new EnumMap<>(AvailabilityStatus.class);
    for (NavigableMap<LocalDate, DailySale> m : source.explicitDays().values()) {
      for (DailySale s : m.values()) {
        out.merge(s.status(), 1L, Long::sum);
      }
    }
    return out;
  }
}
