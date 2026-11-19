package com.pmart.mfc.inbound.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 센터 관리자 웹 입고 목록(MB-1, 2026-11-18) — 센터·상태(·기간)로 ID 만 고른다. 표·열 이름은 코드 상수만. */
@Repository
public class InboundListStore {

  public enum Kind {
    INITIAL("initial_stock_load", "receipt_id", "created_at", false),
    SUPPLY("supply_request", "supply_request_id", "submitted_at", true),
    RETURN("return_receipt", "return_receipt_id", "received_at", true);

    final String table;
    final String id;
    final String time;
    final boolean newestFirst;

    Kind(String table, String id, String time, boolean newestFirst) {
      this.table = table;
      this.id = id;
      this.time = time;
      this.newestFirst = newestFirst;
    }
  }

  private final NamedParameterJdbcTemplate jdbc;

  public InboundListStore(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<String> ids(
      Kind k,
      String mfcId,
      @Nullable Collection<String> states,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable String after,
      int limit) {
    return ids(k, mfcId, states, null, from, to, after, limit);
  }

  /** (0.15.0, PDA BR-52 R12) {@code inspectionState} 는 회수품 반입만 — 주면 오래된 순(검수 대기 큐). */
  public List<String> ids(
      Kind k,
      String mfcId,
      @Nullable Collection<String> states,
      @Nullable String inspectionState,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable String after,
      int limit) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("mfc", mfcId);
    args.put("limit", limit);
    StringBuilder sql =
        new StringBuilder("SELECT " + k.id + " FROM " + k.table + " WHERE mfc_id = :mfc");
    if (states != null && !states.isEmpty()) {
      sql.append(" AND state IN (:states)");
      args.put("states", states);
    }
    if (inspectionState != null) {
      if (k != Kind.RETURN) {
        throw new IllegalArgumentException("inspectionState 는 회수품 반입만");
      }
      sql.append(" AND inspection_state = :inspection");
      args.put("inspection", inspectionState);
    }
    if (from != null) {
      sql.append(" AND ").append(k.time).append(" >= :from");
      args.put("from", Timestamp.from(from));
    }
    if (to != null) {
      sql.append(" AND ").append(k.time).append(" < :to");
      args.put("to", Timestamp.from(to));
    }
    boolean newestFirst = k.newestFirst && inspectionState == null;
    String cmp = newestFirst ? "<" : ">";
    String dir = newestFirst ? " DESC" : "";
    if (after != null) {
      sql.append(" AND (")
          .append(k.time)
          .append(", ")
          .append(k.id)
          .append(") ")
          .append(cmp)
          .append(" (SELECT ")
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
    sql.append(" ORDER BY ")
        .append(k.time)
        .append(dir)
        .append(", ")
        .append(k.id)
        .append(dir)
        .append(" LIMIT :limit");
    return jdbc.queryForList(sql.toString(), args, String.class);
  }
}
