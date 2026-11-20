package com.pmart.mfc.capacity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 센터 재고 실사 시간대(V20, 2026-11-20) — 바꾼 센터만 행이 있다. */
@Repository
public class CountWindowStore {

  /** windows = "07:00-08:00,20:00-21:00"(빈 문자열 = 없음). */
  public record Row(
      String mfcId, String windows, int version, String updatedBy, @Nullable Instant updatedAt) {}

  private final JdbcTemplate jdbc;

  public CountWindowStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Row> find(String mfcId, boolean lock) {
    return jdbc
        .query(
            "SELECT mfc_id, windows, version, updated_by, updated_at"
                + " FROM count_window_setting WHERE mfc_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, i) ->
                new Row(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getInt(3),
                    rs.getString(4),
                    rs.getTimestamp(5).toInstant()),
            mfcId)
        .stream()
        .findFirst();
  }

  public void save(String mfcId, String windows, int version, String by, Instant at) {
    jdbc.update(
        "INSERT INTO count_window_setting (mfc_id, windows, version, updated_by, updated_at)"
            + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (mfc_id) DO UPDATE SET"
            + " windows = EXCLUDED.windows, version = EXCLUDED.version,"
            + " updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
        mfcId,
        windows,
        version,
        by,
        Timestamp.from(at));
  }
}
