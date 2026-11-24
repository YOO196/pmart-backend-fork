package com.pmart.mfc.inventory.api;

import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.infrastructure.StockStreamListener;
import com.pmart.mfc.inventory.infrastructure.StockStreamStore;
import com.pmart.mfc.inventory.infrastructure.StockStreamStore.Row;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 재고 실시간 스트림 보내기(0.46.0, SSE). 생성된 계약 메서드는 {@code ResponseEntity} 라 흘려보낼 수 없어서 서블릿 비동기로 직접 쓴다
 * (delivery-tracking {@code EtaStreams} 와 같은 방식). 원천은 {@link StockStreamListener} 의 알림(커밋 뒤) — 그 줄을
 * 한 번 읽어 센터가 맞는 구독마다 줄에 넣고, 쓰기 스레드 묶음이 연결마다 한 번에 하나씩 쓴다(막힌 연결이 다른 구독을 늦추지 않게). 막혀 있거나 밀린 것이 너무 많으면
 * 닫는다(화면은 다시 접속).
 */
@Component
class StockStreams {

  private static final Logger log = LoggerFactory.getLogger(StockStreams.class);
  private static final String HEARTBEAT = ": hb\n\n";

  /** 이어 받기 — 알림 순서(stream_id)와 커밋 순서가 다를 수 있어 앞으로 여유를 둔다. */
  static final Duration RESUME_MARGIN = Duration.ofSeconds(10);

  private static final int RESUME_LIMIT = 5000;

  private final class Sub {
    final @Nullable Set<String> centers;
    final AsyncContext ctx;
    final Instant openedAt;
    final ArrayDeque<String> queue = new ArrayDeque<>();

    /** 접속 중(ready·이어 받기 전) 들어온 실시간 이벤트 — 이어 받기 뒤에 보낸다. */
    @Nullable List<String> pending = new ArrayList<>();

    boolean draining;
    volatile @Nullable Instant writeStartedAt;
    volatile Instant lastSent;
    volatile boolean closed;

    Sub(@Nullable Set<String> centers, AsyncContext ctx, Instant now) {
      this.centers = centers;
      this.ctx = ctx;
      this.openedAt = now;
      this.lastSent = now;
    }

    boolean wants(String mfcId) {
      return centers == null || centers.contains(mfcId);
    }
  }

  private final List<Sub> subs = new CopyOnWriteArrayList<>();
  private final StockStreamStore store;
  private final StockStreamListener listener;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Duration heartbeat;
  private final Duration maxDuration;
  private final Duration retention;
  private final Duration writeTimeout;
  private final int maxConnections;
  private final int maxQueue;
  private final ExecutorService writers;
  private final ScheduledExecutorService ticker;
  private volatile Instant lastCleanup = Instant.EPOCH;

  StockStreams(
      StockStreamStore store,
      StockStreamListener listener,
      ObjectMapper mapper,
      Clock clock,
      @Value("${pmart.inventory.stock-stream.heartbeat:PT5S}") Duration heartbeat,
      @Value("${pmart.inventory.stock-stream.max-duration:PT30M}") Duration maxDuration,
      @Value("${pmart.inventory.stock-stream.retention:PT1H}") Duration retention,
      @Value("${pmart.inventory.stock-stream.write-timeout:PT30S}") Duration writeTimeout,
      @Value("${pmart.inventory.stock-stream.max-connections:500}") int maxConnections,
      @Value("${pmart.inventory.stock-stream.max-queue:2000}") int maxQueue,
      @Value("${pmart.inventory.stock-stream.write-threads:4}") int writeThreads) {
    this.store = store;
    this.listener = listener;
    this.mapper = mapper;
    this.clock = clock;
    this.heartbeat = heartbeat;
    this.maxDuration = maxDuration;
    this.retention = retention;
    this.writeTimeout = writeTimeout;
    this.maxConnections = maxConnections;
    this.maxQueue = maxQueue;
    AtomicInteger n = new AtomicInteger();
    this.writers =
        Executors.newFixedThreadPool(
            Math.max(1, writeThreads),
            r -> {
              Thread t = new Thread(r, "stock-stream-write-" + n.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
    this.ticker =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "stock-stream-tick");
              t.setDaemon(true);
              return t;
            });
    this.ticker.scheduleWithFixedDelay(
        () -> {
          try {
            tick();
          } catch (RuntimeException e) {
            log.warn("재고 스트림 점검 실패: {}", e.toString());
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
    listener.start(this::notified, this::reconnected);
  }

  @jakarta.annotation.PreDestroy
  void stop() {
    listener.stop();
    ticker.shutdownNow();
    writers.shutdownNow();
  }

  /** 접속 — 권한 판정이 끝난 센터 범위로(null = 모든 센터). */
  void open(
      HttpServletRequest req,
      HttpServletResponse res,
      @Nullable Set<String> centers,
      @Nullable String lastEventId) {
    if (subs.size() >= maxConnections) {
      throw new PmartException(
          "STREAM_LIMIT",
          HttpStatus.SERVICE_UNAVAILABLE,
          "실시간 구독이 많습니다. 잠시 뒤 다시 접속하거나 조회를 쓰세요.",
          true,
          List.of());
    }
    res.setStatus(200);
    res.setContentType("text/event-stream");
    res.setCharacterEncoding("UTF-8");
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Accel-Buffering", "no");
    AsyncContext ctx = req.startAsync();
    ctx.setTimeout(maxDuration.toMillis() + 60_000);
    Sub s = new Sub(centers, ctx, clock.instant());
    ctx.addListener(
        new AsyncListener() {
          @Override
          public void onComplete(AsyncEvent e) {
            s.closed = true;
            subs.remove(s);
          }

          @Override
          public void onTimeout(AsyncEvent e) {
            close(s);
          }

          @Override
          public void onError(AsyncEvent e) {
            close(s);
          }

          @Override
          public void onStartAsync(AsyncEvent e) {}
        });
    // 먼저 구독에 넣는다 — 그 뒤 커밋은 pending 에 모였다가 ready·이어 받기 뒤에 나간다(빠짐 없음, 겹침은 version 으로)
    subs.add(s);
    List<String> first = new ArrayList<>();
    Row from = resumeRow(lastEventId);
    boolean resumed = from != null;
    first.add(ready(centers, resumed));
    if (lastEventId != null && from == null) {
      first.add(resync("RESUME_UNAVAILABLE"));
    }
    if (from != null) {
      List<Row> rows = store.since(from.createdAt().minus(RESUME_MARGIN), RESUME_LIMIT);
      if (rows.size() >= RESUME_LIMIT) {
        first.add(resync("BACKLOG"));
      } else {
        for (Row r : rows) {
          if (s.wants(r.mfcId())) {
            first.addAll(frames(r));
          }
        }
      }
    }
    synchronized (s) {
      List<String> live = s.pending;
      s.pending = null;
      s.queue.addAll(first);
      if (live != null) {
        s.queue.addAll(live);
      }
    }
    drain(s);
  }

  private @Nullable Row resumeRow(@Nullable String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) {
      return null;
    }
    long id;
    try {
      id = Long.parseLong(lastEventId.trim());
    } catch (NumberFormatException e) {
      return null;
    }
    Row r = store.find(id);
    if (r == null || r.createdAt().isBefore(clock.instant().minus(retention))) {
      return null;
    }
    return r;
  }

  /** LISTEN 알림 — 커밋된 줄 하나. */
  void notified(long streamId) {
    if (subs.isEmpty()) {
      return;
    }
    Row r = store.find(streamId);
    if (r == null) {
      return; // 이미 지워짐
    }
    List<String> fs = null;
    for (Sub s : subs) {
      if (s.closed || !s.wants(r.mfcId())) {
        continue;
      }
      if (fs == null) {
        fs = frames(r);
      }
      enqueue(s, fs);
    }
  }

  /** LISTEN 연결을 다시 잡음 — 그 사이 놓쳤을 수 있다. */
  void reconnected() {
    String f = resync("LISTENER_RECONNECTED");
    for (Sub s : subs) {
      enqueue(s, List.of(f));
    }
  }

  private void enqueue(Sub s, List<String> fs) {
    boolean overflow = false;
    synchronized (s) {
      if (s.closed) {
        return;
      }
      List<String> pending = s.pending;
      if (pending != null) {
        pending.addAll(fs);
        return;
      }
      s.queue.addAll(fs);
      if (s.queue.size() > maxQueue) {
        overflow = true;
      }
    }
    if (overflow) {
      log.warn("재고 스트림 구독 밀림 {}건 넘음 — 닫음", maxQueue);
      close(s);
      return;
    }
    drain(s);
  }

  private void drain(Sub s) {
    synchronized (s) {
      if (s.draining || s.queue.isEmpty() || s.closed) {
        return;
      }
      s.draining = true;
    }
    try {
      writers.execute(() -> write(s));
    } catch (RejectedExecutionException e) {
      close(s); // 끄는 중
    }
  }

  private void write(Sub s) {
    try {
      while (true) {
        String f;
        synchronized (s) {
          f = s.queue.poll();
          if (f == null || s.closed) {
            s.draining = false;
            return;
          }
        }
        s.writeStartedAt = clock.instant();
        PrintWriter out = s.ctx.getResponse().getWriter();
        out.write(f);
        out.flush();
        s.writeStartedAt = null;
        if (out.checkError()) {
          throw new IOException("client gone");
        }
        s.lastSent = clock.instant();
      }
    } catch (IOException | RuntimeException e) {
      synchronized (s) {
        s.draining = false;
      }
      close(s);
    }
  }

  void tick() {
    Instant now = clock.instant();
    for (Sub s : subs) {
      if (s.closed) {
        subs.remove(s);
        continue;
      }
      Instant started = s.writeStartedAt;
      if (started != null && !now.isBefore(started.plus(writeTimeout))) {
        log.warn("재고 스트림 구독 쓰기가 {} 넘게 막힘 — 닫음", writeTimeout);
        close(s);
        continue;
      }
      if (!now.isBefore(s.openedAt.plus(maxDuration))) {
        close(s); // 최대 연결 시간 — 화면은 Last-Event-ID 로 다시 접속
        continue;
      }
      if (!now.isBefore(s.lastSent.plus(heartbeat))) {
        enqueue(s, List.of(HEARTBEAT));
      }
    }
    if (!now.isBefore(lastCleanup.plus(Duration.ofMinutes(5)))) {
      lastCleanup = now;
      store.deleteBefore(now.minus(retention));
    }
  }

  /** 한 줄 → stock-changed(+ 가용이 0 경계를 넘었으면 summary-changed). */
  private List<String> frames(Row r) {
    JsonNode p = mapper.readTree(r.payload());
    String id = Long.toString(r.streamId());
    ObjectNode changed = mapper.createObjectNode();
    changed.put("type", "stock-changed");
    changed.put("eventId", id);
    for (String f :
        List.of("mfcId", "commitId", "cause", "occurredAt", "items", "units", "holds")) {
      if (p.has(f)) {
        changed.set(f, p.get(f));
      }
    }
    List<String> out = new ArrayList<>(2);
    out.add(frame(id, "stock-changed", changed));
    JsonNode crossed = p.path("summarySkus");
    if (crossed.isArray() && !crossed.isEmpty()) {
      ObjectNode sum = mapper.createObjectNode();
      sum.put("type", "summary-changed");
      sum.put("eventId", id);
      sum.set("mfcId", p.get("mfcId"));
      sum.set("skus", crossed);
      out.add(frame(id, "summary-changed", sum));
    }
    return out;
  }

  private String ready(@Nullable Set<String> centers, boolean resumed) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "ready");
    m.put("resumed", resumed);
    m.put("mfcIds", centers == null ? List.of() : List.copyOf(centers));
    return frame(null, "ready", m);
  }

  private String resync(String reason) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "resync");
    m.put("reason", reason);
    return frame(null, "resync", m);
  }

  private String frame(@Nullable String id, String event, Object data) {
    StringBuilder b = new StringBuilder();
    if (id != null) {
      b.append("id: ").append(id).append('\n');
    }
    b.append("event: ").append(event).append('\n');
    b.append("data: ").append(mapper.writeValueAsString(data)).append("\n\n");
    return b.toString();
  }

  private void close(Sub s) {
    synchronized (s) {
      if (s.closed) {
        return;
      }
      s.closed = true;
      s.queue.clear();
    }
    subs.remove(s);
    try {
      s.ctx.complete();
    } catch (IllegalStateException ignored) {
      // 이미 끝남
    }
  }

  int size() {
    return subs.size();
  }
}
