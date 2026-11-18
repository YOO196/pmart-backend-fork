package com.pmart.mfc.capacity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 센터 직원 연락처(V18, 2026-11-18) — 연락 수단 표시만. */
@Repository
public class StaffContactStore {

  public record Contact(
      String mfcId,
      String operatorId,
      @Nullable String name,
      @Nullable String phone,
      @Nullable String email,
      int version,
      @Nullable Instant updatedAt) {}

  private final JdbcTemplate jdbc;

  public StaffContactStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 휴대폰·이메일 중 하나라도 있는 사람, 이름(없으면 운영자 ID) 순. */
  public List<Contact> list(String mfcId) {
    return jdbc.query(
        "SELECT * FROM mfc_staff_contact WHERE mfc_id = ?"
            + " AND (phone IS NOT NULL OR email IS NOT NULL)"
            + " ORDER BY COALESCE(name, operator_id), operator_id",
        (rs, i) -> row(rs),
        mfcId);
  }

  public Optional<Contact> find(String mfcId, String operatorId, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM mfc_staff_contact WHERE mfc_id = ? AND operator_id = ?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, i) -> row(rs),
            mfcId,
            operatorId)
        .stream()
        .findFirst();
  }

  /** 넣거나 고친다(호출자가 다음 version 을 준다). */
  public void save(
      String mfcId,
      String operatorId,
      @Nullable String name,
      @Nullable String phone,
      @Nullable String email,
      int version,
      Instant at) {
    jdbc.update(
        "INSERT INTO mfc_staff_contact (mfc_id, operator_id, name, phone, email, version, updated_at)"
            + " VALUES (?,?,?,?,?,?,?) ON CONFLICT (mfc_id, operator_id) DO UPDATE SET"
            + " name = EXCLUDED.name, phone = EXCLUDED.phone, email = EXCLUDED.email,"
            + " version = EXCLUDED.version, updated_at = EXCLUDED.updated_at",
        mfcId,
        operatorId,
        name,
        phone,
        email,
        version,
        Timestamp.from(at));
  }

  private static Contact row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Contact(
        rs.getString("mfc_id"),
        rs.getString("operator_id"),
        rs.getString("name"),
        rs.getString("phone"),
        rs.getString("email"),
        rs.getInt("version"),
        rs.getTimestamp("updated_at").toInstant());
  }
}
