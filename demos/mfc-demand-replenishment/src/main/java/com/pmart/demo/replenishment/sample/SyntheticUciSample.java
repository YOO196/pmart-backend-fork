package com.pmart.demo.replenishment.sample;

import com.pmart.demo.replenishment.csv.Csv;
import com.pmart.demo.replenishment.sales.Season;
import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Random;

/**
 * UCI Online Retail II 와 같은 열의 합성 CSV(명세 6장 "합성 CSV 샘플로 다운로드 없이 기본 배치 검수"). 고정 시드라 매번 같다. 실제
 * 거래·고객이 아니다(고객 ID 는 비운다).
 *
 * <p>UCI 와 닮게: 2009-12-01 ~ 2011-12-09, 토요일과 연말(12-23 ~ 01-03) 센터 무판매. SKU 7개가 정상·간헐·늦은 시작·판매 끊김·겨울
 * 성수기·도매 이상치·드문 판매를 하나씩 맡는다. 정제가 걸러야 할 줄(취소·음수·단가 0·비상품 코드·영국 밖)을 섞는다.
 */
public final class SyntheticUciSample {

  static final LocalDate FIRST = LocalDate.of(2009, 12, 1);
  static final LocalDate LAST = LocalDate.of(2011, 12, 9);
  private static final String[] HEADER = {
    "Invoice", "StockCode", "Description", "Quantity", "InvoiceDate", "Price", "Customer ID", "Country"
  };

  private SyntheticUciSample() {}

  public static void main(String[] args) throws IOException {
    Path out = Path.of(args.length > 0 ? args[0] : "sample/uci-synthetic.csv");
    write(out);
    System.out.println("합성 샘플: " + out.toAbsolutePath());
  }

  public static boolean centerOpen(LocalDate d) {
    if (d.getDayOfWeek() == DayOfWeek.SATURDAY) {
      return false;
    }
    boolean xmas =
        (d.getMonthValue() == 12 && d.getDayOfMonth() >= 23) || (d.getMonthValue() == 1 && d.getDayOfMonth() <= 3);
    return !xmas;
  }

  public static void write(Path out) throws IOException {
    Random rnd = new Random(20261124L);
    int invoice = 489000;
    try (Csv.Writer w = new Csv.Writer(out, HEADER)) {
      for (LocalDate d = FIRST; !d.isAfter(LAST); d = d.plusDays(1)) {
        if (!centerOpen(d)) {
          continue;
        }
        boolean summer = Season.of(d) == Season.SUMMER;
        boolean winter = Season.of(d) == Season.WINTER;
        double dow =
            switch (d.getDayOfWeek()) {
              case MONDAY -> 1.1;
              case THURSDAY -> 1.2;
              case FRIDAY -> 0.9;
              case SUNDAY -> 0.6;
              default -> 1.0;
            };
        // 10001 정상 판매
        invoice = sale(w, rnd, invoice, d, "10001", "STEADY MUG", poisson(rnd, 20 * dow * (summer ? 1.15 : winter ? 0.9 : 1)), 2.10);
        // 10002 간헐
        if (rnd.nextDouble() < 0.2) {
          invoice = sale(w, rnd, invoice, d, "10002", "INTERMITTENT LAMP", 1 + rnd.nextInt(6), 7.95);
        }
        // 10003 늦은 시작
        if (!d.isBefore(LocalDate.of(2010, 3, 1))) {
          invoice = sale(w, rnd, invoice, d, "10003", "LATE START BAG", poisson(rnd, 8 * dow), 1.25);
        }
        // 10004 판매 끊김
        if (!d.isAfter(LocalDate.of(2010, 9, 30))) {
          invoice = sale(w, rnd, invoice, d, "10004", "DISCONTINUED TIN", poisson(rnd, 6), 3.75);
        }
        // 10005 겨울 성수기
        invoice = sale(w, rnd, invoice, d, "10005", "WINTER CANDLE", poisson(rnd, winter ? 15 : summer ? 3 : 7), 1.65);
        // 10006 도매 이상치
        int wholesale = rnd.nextDouble() < 0.01 ? 800 : 0;
        invoice = sale(w, rnd, invoice, d, "10006", "WHOLESALE CARD", poisson(rnd, 4) + wholesale, 0.42);
        // 10007 드문 판매
        if (rnd.nextDouble() < 0.04) {
          invoice = sale(w, rnd, invoice, d, "10007", "SPARSE FRAME", 1 + rnd.nextInt(3), 12.50);
        }
        // 걸러야 할 줄
        if (rnd.nextDouble() < 0.15) {
          String ts = d + " 16:05:00";
          switch (rnd.nextInt(8)) {
            case 0 -> w.row("C" + invoice++, "10001", "STEADY MUG", -3, ts, 2.10, "", "United Kingdom");
            case 1 -> w.row(invoice++, "10005", "WINTER CANDLE", -2, ts, 1.65, "", "United Kingdom");
            case 2 -> w.row(invoice++, "10001", "STEADY MUG", 5, ts, 0, "", "United Kingdom");
            case 3 -> w.row(invoice++, "POST", "POSTAGE", 1, ts, 18.00, "", "United Kingdom");
            case 4 -> w.row(invoice++, "DOT", "DOTCOM POSTAGE", 1, ts, 9.40, "", "United Kingdom");
            case 5 -> w.row(invoice++, "BANK CHARGES", "Bank Charges", 1, ts, 15.00, "", "United Kingdom");
            case 6 -> w.row(invoice++, "TEST001", "This is a test product.", 1, ts, 4.50, "", "United Kingdom");
            default -> w.row(invoice++, "10001", "STEADY MUG", 12, ts, 2.10, "", "France");
          }
        }
      }
    }
  }

  /** 한 SKU·날의 판매를 1~2줄로 나눠 쓴다. qty 0 이면 줄 없음. */
  private static int sale(Csv.Writer w, Random rnd, int invoice, LocalDate d, String code, String desc, int qty, double price)
      throws IOException {
    if (qty <= 0) {
      return invoice;
    }
    int first = qty > 1 && rnd.nextBoolean() ? 1 + rnd.nextInt(qty - 1) : qty;
    w.row(invoice, code, desc, first, d + " 10:" + String.format("%02d", rnd.nextInt(60)) + ":00", price, "", "United Kingdom");
    invoice++;
    if (first < qty) {
      w.row(invoice, code, desc, qty - first, d + " 14:" + String.format("%02d", rnd.nextInt(60)) + ":00", price, "", "United Kingdom");
      invoice++;
    }
    return invoice;
  }

  static int poisson(Random rnd, double mean) {
    double l = Math.exp(-mean);
    int k = 0;
    double p = 1;
    do {
      k++;
      p *= rnd.nextDouble();
    } while (p > l);
    return k - 1;
  }
}
