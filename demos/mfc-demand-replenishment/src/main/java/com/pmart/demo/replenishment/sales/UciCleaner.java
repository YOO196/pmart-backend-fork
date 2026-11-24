package com.pmart.demo.replenishment.sales;

import com.pmart.demo.replenishment.csv.Csv;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * UCI Online Retail II(CSV 로 바꾼 것) 정제·일 합계(명세 6장 구현 지시 1).
 *
 * <p>열: Invoice, StockCode, Description, Quantity, InvoiceDate(영국 현지 시각), Price, Customer ID,
 * Country. 고객 ID 는 읽지 않는다.
 */
public final class UciCleaner {

  public static final String MFC_ID = "DEMO_UK_01";
  public static final String SALES_BASIS = "INVOICE_ORDER_CONFIRMED_PROXY";
  public static final String SOURCE_TIMEZONE = "Europe/London";

  /** 제외 사유. 앞에서부터 처음 맞는 사유 하나로 센다. */
  public enum Exclusion {
    INVALID_ROW,
    NON_UK,
    CANCELLED_INVOICE,
    NON_POSITIVE_QTY,
    NON_POSITIVE_PRICE,
    NON_PRODUCT_CODE
  }

  /**
   * 비상품 코드(2026-11-24 실제 UCI 프로파일링으로 확정 — 설명): POST 배송비, DOT 닷컴 배송비, M·m 수동, C2 운송비, D 할인, S 샘플,
   * BANK CHARGES 은행 수수료, AMAZONFEE, CRUK 수수료, B 대손 조정. 그 밖에 TEST·ADJUST·GIFT 로 시작하는 코드(시험 상품·조정·
   * 상품권). 숫자가 없어도 실물 상품인 코드(DCGSSBOY·DCGSSGIRL 파티 백, PADS 쿠션 패드)는 남긴다 — 숫자 유무로 추측하지 않고, 남긴
   * 숫자 없는 코드는 보고서에 따로 띄운다({@link Result#keptCodesWithoutDigit}).
   */
  static final Set<String> NON_PRODUCT_CODES =
      Set.of("POST", "DOT", "M", "C2", "D", "S", "BANK CHARGES", "AMAZONFEE", "CRUK", "B");

  private static final Pattern NON_PRODUCT_PREFIX =
      Pattern.compile("^(TEST|ADJUST|GIFT).*", Pattern.CASE_INSENSITIVE);
  private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");
  private static final DateTimeFormatter[] DATE_FORMATS = {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    DateTimeFormatter.ISO_LOCAL_DATE_TIME
  };

  /** 정제 결과와 보고. */
  public record Result(
      SalesSource source,
      long readLines,
      long keptLines,
      Map<Exclusion, Long> excluded,
      Map<String, Long> excludedNonProductCodes,
      Map<String, Long> keptCodesWithoutDigit) {}

  private UciCleaner() {}

  public static boolean isNonProductCode(String stockCode) {
    String code = stockCode.trim();
    return NON_PRODUCT_CODES.contains(code.toUpperCase(Locale.ROOT))
        || NON_PRODUCT_PREFIX.matcher(code).matches();
  }

  public static Result clean(Path csv) throws IOException {
    Map<Exclusion, Long> excluded = new EnumMap<>(Exclusion.class);
    for (Exclusion e : Exclusion.values()) {
      excluded.put(e, 0L);
    }
    Map<String, Long> nonProduct = new TreeMap<>();
    Map<String, Long> keptNoDigit = new TreeMap<>();
    Map<String, NavigableMap<LocalDate, Long>> daily = new TreeMap<>();
    TreeSet<LocalDate> centerDates = new TreeSet<>();
    long[] counts = new long[2];
    LocalDate[] range = new LocalDate[2];

    Csv.read(
        csv,
        row -> {
          counts[0]++;
          String invoice;
          String code;
          String country;
          LocalDate date;
          long qty;
          BigDecimal price;
          try {
            invoice = row.get("Invoice").trim();
            code = row.get("StockCode").trim();
            country = row.get("Country").trim();
            date = parseDate(row.get("InvoiceDate"));
            qty = Long.parseLong(row.get("Quantity").trim());
            price = new BigDecimal(row.get("Price").trim());
          } catch (RuntimeException e) {
            excluded.merge(Exclusion.INVALID_ROW, 1L, Long::sum);
            return;
          }
          range[0] = range[0] == null || date.isBefore(range[0]) ? date : range[0];
          range[1] = range[1] == null || date.isAfter(range[1]) ? date : range[1];
          if (!"United Kingdom".equalsIgnoreCase(country)) {
            excluded.merge(Exclusion.NON_UK, 1L, Long::sum);
          } else if (invoice.toUpperCase(Locale.ROOT).startsWith("C")) {
            excluded.merge(Exclusion.CANCELLED_INVOICE, 1L, Long::sum);
          } else if (qty <= 0) {
            excluded.merge(Exclusion.NON_POSITIVE_QTY, 1L, Long::sum);
          } else if (price.signum() <= 0) {
            excluded.merge(Exclusion.NON_POSITIVE_PRICE, 1L, Long::sum);
          } else if (isNonProductCode(code)) {
            excluded.merge(Exclusion.NON_PRODUCT_CODE, 1L, Long::sum);
            nonProduct.merge(code, 1L, Long::sum);
          } else {
            counts[1]++;
            if (!HAS_DIGIT.matcher(code).matches()) {
              keptNoDigit.merge(code, 1L, Long::sum);
            }
            daily
                .computeIfAbsent(code.toUpperCase(Locale.ROOT), k -> new TreeMap<>())
                .merge(date, qty, Long::sum);
            centerDates.add(date);
          }
        });
    if (range[0] == null) {
      throw new IllegalArgumentException("읽을 수 있는 줄이 없음: " + csv);
    }
    SalesSource source =
        new SalesSource(MFC_ID, SALES_BASIS, SOURCE_TIMEZONE, range[0], range[1], daily, centerDates);
    return new Result(source, counts[0], counts[1], excluded, nonProduct, keptNoDigit);
  }

  static LocalDate parseDate(String raw) {
    String s = raw.trim();
    for (DateTimeFormatter f : DATE_FORMATS) {
      try {
        return LocalDateTime.parse(s, f).toLocalDate();
      } catch (DateTimeParseException ignored) {
        // 다음 형식
      }
    }
    return LocalDate.parse(s);
  }
}
