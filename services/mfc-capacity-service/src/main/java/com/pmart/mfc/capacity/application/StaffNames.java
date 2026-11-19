package com.pmart.mfc.capacity.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.infrastructure.EmployeeNameClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 직원 화면 표기(0.23.0, ADR-0070 — 이름 · 사번, 운영자 ID 는 화면에 내지 않는다). 이름 = hr-service 내부 조회, 사번 = 인사 사본
 * {@code hr_employee}.
 */
@Service
public class StaffNames {

  public record StaffName(String operatorId, @Nullable String name, @Nullable String employeeNo) {}

  static final int MAX = 200;

  private final EmployeeNameClient hr;
  private final NamedParameterJdbcTemplate jdbc;

  public StaffNames(EmployeeNameClient hr, NamedParameterJdbcTemplate jdbc) {
    this.hr = hr;
    this.jdbc = jdbc;
  }

  /** 센터 화면용 — 그 센터 운영자, 1~200개. 모르는 ID 는 빠진다. */
  public List<StaffName> forCenter(String mfcId, @Nullable List<String> operatorIds) {
    AssignmentService.requireReader(mfcId);
    if (operatorIds == null || operatorIds.isEmpty() || operatorIds.size() > MAX) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "operatorIds 는 1~" + MAX + "개입니다.");
    }
    Map<String, StaffName> m = lookup(operatorIds);
    List<StaffName> out = new ArrayList<>();
    for (String id : new LinkedHashSet<>(operatorIds)) {
      StaffName s = m.get(id);
      if (s != null && (s.name() != null || s.employeeNo() != null)) {
        out.add(s);
      }
    }
    return out;
  }

  /** 권한 검사 없이(호출자가 이미 확인) — 운영자 ID → 이름·사번. */
  public Map<String, StaffName> lookup(Collection<String> operatorIds) {
    Map<String, StaffName> out = new LinkedHashMap<>();
    if (operatorIds.isEmpty()) {
      return out;
    }
    Map<String, String> names = hr.names(operatorIds);
    Map<String, String> nos = new LinkedHashMap<>();
    jdbc.query(
        "SELECT operator_id, employee_no FROM hr_employee WHERE operator_id IN (:ids)",
        Map.of("ids", new ArrayList<>(new LinkedHashSet<>(operatorIds))),
        rs -> {
          nos.put(rs.getString("operator_id"), rs.getString("employee_no"));
        });
    for (String id : new LinkedHashSet<>(operatorIds)) {
      out.put(id, new StaffName(id, names.get(id), nos.get(id)));
    }
    return out;
  }
}
