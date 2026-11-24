package com.pmart.mfc.inventory.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 재고 실시간 스트림 표(V31 {@code stock_stream_event}, 0.46.0). 쓰기는 원장 반영과 같은 트랜잭션에서 하고 {@code pg_notify} 로
 * 알린다 — PostgreSQL 은 커밋 뒤에만, LISTEN 중인 모든 연결에 알림을 보낸다(롤백이면 안 감).
 */
@Repository
public class StockStreamStore {

  /** LISTEN 채널 이름. */
  public static final String CHANNEL = "mfc_stock_stream";

  /** 한 줄. payload 는 JSON 문자열(StockStreamEvent stock-changed data 와 같은 모양, eventId 제외). */
  public record Row(long streamId, String mfcId, String payload, Instant createdAt) {}

  private static final RowMapper<Row> ROW =
      (rs, i) ->
          new Row(
              rs.getLong("stream_id"),
              rs.getString("mfc_id"),
              rs.getString("payload"),
              rs.getTimestamp("created_at").toInstant());

  private final JdbcTemplate jdbc;

  public StockStreamStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 한 줄 쓰고 알린다(호출자 트랜잭션 안). */
  public long append(
      String mfcId, String commitId, String commitType, Instant occurredAt, String payload) {
    Long id =
        jdbc.queryForObject(
            "INSERT INTO stock_stream_event (mfc_id, commit_id, commit_type, occurred_at, payload)"
                + " VALUES (?,?,?,?,?::jsonb) RETURNING stream_id",
            Long.class,
            mfcId,
            commitId,
            commitType,
            Timestamp.from(occurredAt),
            payload);
    long streamId = id == null ? 0 : id;
    jdbc.queryForList("SELECT pg_notify(?, ?)", CHANNEL, Long.toString(streamId));
    return streamId;
  }

  public @Nullable Row find(long streamId) {
    List<Row> rows =
        jdbc.query(
            "SELECT stream_id, mfc_id, payload::text AS payload, created_at FROM stock_stream_event"
                + " WHERE stream_id = ?",
            ROW,
            streamId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 이어 받기 — created_at 이 from 이후인 줄(센터 거르기는 부르는 쪽), 만든 순. */
  public List<Row> since(Instant from, int limit) {
    return jdbc.query(
        "SELECT stream_id, mfc_id, payload::text AS payload, created_at FROM stock_stream_event"
            + " WHERE created_at >= ? ORDER BY created_at, stream_id LIMIT ?",
        ROW,
        Timestamp.from(from),
        limit);
  }

  /** 보존 기간 밖 줄 지우기. */
  public int deleteBefore(Instant before) {
    return jdbc.update(
        "DELETE FROM stock_stream_event WHERE created_at < ?", Timestamp.from(before));
  }
}
