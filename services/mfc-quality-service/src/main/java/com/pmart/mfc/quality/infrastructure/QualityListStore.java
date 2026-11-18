package com.pmart.mfc.quality.infrastructure;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 센터 관리자 웹 품질 목록(MB-1, 2026-11-18) — 센터·상태로 ID 만 고른다(대기 큐라 오래된 순, 커서 = 마지막 ID). */
@Repository
public class QualityListStore {

  /** 목록 대상 표. 표·열 이름은 코드 상수만(사용자 입력이 아님). */
  public enum Kind {
    DECISION("quality_decision", "decision_id", "created_at", "state"),
    DISPOSAL("disposal_approval", "disposal_approval_id", "requested_at", "state"),
    INSPECTION("return_inspection", "return_inspection_id", "inspected_at", "apply_status");

    final String table;
    final String id;
    final String time;
    final String state;

    Kind(String table, String id, String time, String state) {
      this.table = table;
      this.id = id;
      this.time = time;
      this.state = state;
    }
  }

  private final NamedParameterJdbcTemplate jdbc;

  public QualityListStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<String> ids(
      Kind k, String mfcId, Collection<String> states, @Nullable String after, int limit) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("mfc", mfcId);
    args.put("states", states);
    args.put("limit", limit);
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + k.id
                + " FROM "
                + k.table
                + " WHERE mfc_id = :mfc AND "
                + k.state
                + " IN (:states)");
    if (after != null) {
      sql.append(" AND (")
          .append(k.time)
          .append(", ")
          .append(k.id)
          .append(") > (SELECT ")
          .append(k.time)
          .append(", ")
          .append(k.id)
          .append(" FROM ")
          .append(k.table)
          .append(" WHERE ")
          .append(k.id)
          .append(" = :after)");
      args.put("after", after);
    }
    sql.append(" ORDER BY ").append(k.time).append(", ").append(k.id).append(" LIMIT :limit");
    return jdbc.queryForList(sql.toString(), args, String.class);
  }
}
