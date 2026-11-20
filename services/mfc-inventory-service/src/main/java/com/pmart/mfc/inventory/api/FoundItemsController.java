package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.model.CountFoundItem;
import com.pmart.mfc.inventory.api.generated.model.ListCountFoundItems200Response;
import com.pmart.mfc.inventory.api.generated.model.ResolveCountFoundItemRequest;
import com.pmart.mfc.inventory.application.FoundItemService;
import com.pmart.mfc.inventory.infrastructure.FoundItemStore.FoundRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;

/**
 * 실사 발견품 조사(0.43.0) — 생성 인터페이스 CountsApi 의 세 경로. {@link CountsController} 가 이 객체에 넘긴다(같은 인터페이스를 두
 * 컨트롤러가 구현할 수 없어서). 업무 규칙은 {@link FoundItemService}.
 */
final class FoundItemsController {

  private final FoundItemService service;

  FoundItemsController(FoundItemService service) {
    this.service = service;
  }

  ResponseEntity<ListCountFoundItems200Response> list(
      String mfcId, @Nullable String state, @Nullable Integer size, @Nullable String cursor) {
    var page = service.list(mfcId, state, size == null ? 50 : size, cursor);
    var out = new ListCountFoundItems200Response();
    out.setItems(page.items().stream().map(FoundItemsController::dto).toList());
    out.setNextCursor(page.nextCursor());
    return ResponseEntity.ok(out);
  }

  ResponseEntity<CountFoundItem> get(String mfcId, String foundId) {
    return ResponseEntity.ok(dto(service.get(mfcId, foundId)));
  }

  ResponseEntity<CountFoundItem> resolve(
      String mfcId,
      String foundId,
      String idempotencyKey,
      String sourceSystem,
      ResolveCountFoundItemRequest body) {
    return ResponseEntity.ok(
        dto(
            service.resolve(
                mfcId,
                foundId,
                idempotencyKey,
                sourceSystem,
                body.getOutcome().getValue(),
                body.getOutcomeRef(),
                body.getLotId(),
                body.getNote())));
  }

  static CountFoundItem dto(FoundRow r) {
    var x = new CountFoundItem();
    x.setFoundId(r.foundId());
    x.setMfcId(r.mfcId());
    x.setSessionId(r.sessionId());
    x.setLastSessionId(r.lastSessionId());
    x.setSeenCount(r.seenCount());
    x.setLocationCode(r.locationCode());
    x.setSku(r.sku());
    x.setBarcode(r.barcode());
    x.setExpiryDate(r.expiryDate());
    x.setQty(r.qty());
    x.setCause(CountFoundItem.CauseEnum.fromValue(r.cause()));
    x.setState(CountFoundItem.StateEnum.fromValue(r.state()));
    x.setOutcome(r.outcome() == null ? null : CountFoundItem.OutcomeEnum.fromValue(r.outcome()));
    x.setOutcomeRef(r.outcomeRef());
    x.setLotId(r.lotId());
    x.setNote(r.note());
    x.setResolvedBy(r.resolvedBy());
    x.setResolvedAt(
        r.resolvedAt() == null ? null : OffsetDateTime.ofInstant(r.resolvedAt(), ZoneOffset.UTC));
    x.setCreatedAt(OffsetDateTime.ofInstant(r.createdAt(), ZoneOffset.UTC));
    x.setUpdatedAt(OffsetDateTime.ofInstant(r.updatedAt(), ZoneOffset.UTC));
    x.setVersion(r.version());
    return x;
  }
}
