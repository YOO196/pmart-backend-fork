package com.pmart.mfc.capacity.api;

import com.pmart.mfc.capacity.api.generated.StaffContactsApi;
import com.pmart.mfc.capacity.api.generated.model.ListStaffContacts200Response;
import com.pmart.mfc.capacity.api.generated.model.PutMyStaffContactRequest;
import com.pmart.mfc.capacity.api.generated.model.StaffContact;
import com.pmart.mfc.capacity.application.StaffContactService;
import com.pmart.mfc.capacity.infrastructure.StaffContactStore.Contact;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 센터 직원 연락처(0.21.0). */
@RestController
public class StaffContactsController implements StaffContactsApi {

  private final StaffContactService service;

  public StaffContactsController(StaffContactService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ListStaffContacts200Response> listStaffContacts(String mfcId) {
    var out = new ListStaffContacts200Response();
    out.setItems(service.list(mfcId).stream().map(StaffContactsController::dto).toList());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<StaffContact> getMyStaffContact(String mfcId) {
    return ResponseEntity.ok(dto(service.mine(mfcId)));
  }

  @Override
  public ResponseEntity<StaffContact> putMyStaffContact(
      String mfcId,
      String idempotencyKey,
      String xSourceSystem,
      PutMyStaffContactRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        dto(
            service.saveMine(
                idempotencyKey,
                xSourceSystem,
                mfcId,
                xExpectedVersion,
                body.getName(),
                body.getPhone(),
                body.getEmail())));
  }

  private static StaffContact dto(Contact c) {
    var out = new StaffContact();
    out.setMfcId(c.mfcId());
    out.setOperatorId(c.operatorId());
    out.setName(c.name());
    out.setPhone(c.phone());
    out.setEmail(c.email());
    out.setVersion(c.version());
    out.setUpdatedAt(c.updatedAt() == null ? null : c.updatedAt().atOffset(ZoneOffset.UTC));
    return out;
  }
}
