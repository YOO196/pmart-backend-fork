package com.pmart.mfc.inventory.api;

import com.pmart.mfc.inventory.api.generated.StockStreamApi;
import com.pmart.mfc.inventory.api.generated.model.StockStreamEvent;
import com.pmart.mfc.inventory.application.StockSummaryService;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 재고 실시간 스트림(0.46.0, SSE). 권한은 {@link StockSummaryService#streamScope}, 보내기는 {@link StockStreams}.
 */
@RestController
public class StockStreamController implements StockStreamApi {

  private final StockSummaryService access;
  private final StockStreams streams;

  StockStreamController(StockSummaryService access, StockStreams streams) {
    this.access = access;
    this.streams = streams;
  }

  /** 응답을 서블릿 비동기로 직접 쓰고 null 을 돌려준다. 권한은 스트림을 열기 전에 판정해 보통 오류 응답으로. */
  @Override
  public @Nullable ResponseEntity<StockStreamEvent> streamStockChanges(
      @Nullable List<String> mfcId, @Nullable String lastEventID) {
    var centers = access.streamScope(mfcId);
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
    streams.open(
        attrs.getRequest(), Objects.requireNonNull(attrs.getResponse()), centers, lastEventID);
    return null;
  }
}
