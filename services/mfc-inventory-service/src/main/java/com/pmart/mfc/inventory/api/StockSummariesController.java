package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.StockApi;
import com.pmart.mfc.inventory.api.generated.model.GetStockSummaries200Response;
import com.pmart.mfc.inventory.api.generated.model.GetStockSummaries200ResponseItemsInner;
import com.pmart.mfc.inventory.application.StockSummaryService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 센터별 품절 상품 수(0.28.0, 요청 IV-5). */
@RestController
public class StockSummariesController implements StockApi {

  private final StockSummaryService service;

  public StockSummariesController(StockSummaryService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<GetStockSummaries200Response> getStockSummaries(@Nullable String mfcId) {
    var out = new GetStockSummaries200Response();
    out.setItems(
        service.summaries(mfcId).stream()
            .map(
                s -> {
                  var x = new GetStockSummaries200ResponseItemsInner();
                  x.setMfcId(s.mfcId());
                  x.setHandledSkuCount(s.handled());
                  x.setSoldOutSkuCount(s.soldOut());
                  return x;
                })
            .toList());
    out.setAsOf(OffsetDateTime.now(ZoneOffset.UTC));
    return ResponseEntity.ok(out);
  }
}
