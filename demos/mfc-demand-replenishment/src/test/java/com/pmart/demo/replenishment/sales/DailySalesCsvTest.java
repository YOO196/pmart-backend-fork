package com.pmart.demo.replenishment.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 명세 4장 일별 판매 형식 읽기(F02: 중복 키·잘못된 수량은 오류, 상태는 원천 그대로, 빠진 날은 MISSING). */
class DailySalesCsvTest {

  static final String HEAD =
      "mfc_id,sku_id,business_date,units_sold,availability_status,sales_basis,source_timezone,unknown_reason\n";

  static Path file(Path dir, String body) throws IOException {
    Path f = dir.resolve("daily.csv");
    Files.writeString(f, HEAD + body, StandardCharsets.UTF_8);
    return f;
  }

  @Test
  void keepsGivenStatusesAndCountsErrors(@TempDir Path dir) throws IOException {
    Path f =
        file(
            dir,
            String.join(
                "\n",
                "M1,A,2026-01-01,3,NORMAL,ORDER_CONFIRMED,Asia/Seoul,",
                "M1,A,2026-01-02,0,NORMAL,ORDER_CONFIRMED,Asia/Seoul,",
                "M1,A,2026-01-03,1,STOCKOUT,ORDER_CONFIRMED,Asia/Seoul,",
                "M1,A,2026-01-03,9,NORMAL,ORDER_CONFIRMED,Asia/Seoul,",
                "M1,A,2026-01-05,-2,NORMAL,ORDER_CONFIRMED,Asia/Seoul,",
                "M1,A,2026-01-06,0,UNKNOWN,ORDER_CONFIRMED,Asia/Seoul,",
                "M1,B,2026-01-01,0,PRE_LAUNCH,ORDER_CONFIRMED,Asia/Seoul,")
                + "\n");
    assertThat(DailySalesCsv.matches(f)).isTrue();
    DailySalesCsv.Result r = DailySalesCsv.read(f);
    assertThat(r.readLines()).isEqualTo(7);
    assertThat(r.keptLines()).isEqualTo(5);
    assertThat(r.errors()).containsEntry("DUPLICATE_DAILY_KEY", 1L).containsEntry("INVALID_ROW", 1L);

    SalesSource src = r.source();
    List<DailySale> days =
        DailyStateAssigner.assign(
            src, "A", src.skuDailyUnits().get("A"), src.centerSaleDates(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 6), 30);
    assertThat(days).extracting(DailySale::status)
        .containsExactly(
            AvailabilityStatus.NORMAL, // 3
            AvailabilityStatus.NORMAL, // 정상 무판매 0
            AvailabilityStatus.STOCKOUT, // 관측 1, 실수요 미확정(첫 줄이 남음)
            AvailabilityStatus.MISSING, // 파일에 없음 → 0 수요로 채우지 않음
            AvailabilityStatus.MISSING, // 음수 줄은 버려짐
            AvailabilityStatus.UNKNOWN);
    assertThat(days.get(2).unitsSold()).isEqualTo(1);
    assertThat(days.get(5).unknownReason()).isEqualTo(UnknownReason.SOURCE_UNAVAILABLE);
    assertThat(src.salesBasis()).isEqualTo("ORDER_CONFIRMED");
  }

  @Test
  void rejectsMixedCentersOrBases(@TempDir Path dir) throws IOException {
    Path f =
        file(
            dir,
            "M1,A,2026-01-01,3,NORMAL,ORDER_CONFIRMED,Asia/Seoul,\nM1,A,2026-01-02,3,NORMAL,HANDED_OVER,Asia/Seoul,\n");
    assertThatThrownBy(() -> DailySalesCsv.read(f)).hasMessageContaining("섞임");
  }
}
