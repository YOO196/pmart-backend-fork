package com.pmart.mfc.inventory.infrastructure;

import java.sql.Connection;
import java.sql.Statement;
import java.util.function.LongConsumer;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 재고 실시간 스트림 알림 받기(0.46.0). 인스턴스마다 연결 하나를 잡고 {@code LISTEN mfc_stock_stream} — PostgreSQL 은 커밋된 알림을
 * LISTEN 중인 모든 연결에 보내므로 여러 인스턴스여도 빠짐없이 받는다(새 인프라 없음). 연결이 끊겨 다시 잡으면 그 사이 알림을 놓쳤을 수 있으니 {@code
 * reconnected} 를 부른다(구독자에게 resync).
 */
@Component
public class StockStreamListener {

  private static final Logger log = LoggerFactory.getLogger(StockStreamListener.class);

  private final DataSource dataSource;
  private volatile boolean running;
  private @Nullable Thread thread;

  public StockStreamListener(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** 받기 시작. notified = stream_id, reconnected = 두 번째 연결부터. */
  public synchronized void start(LongConsumer notified, Runnable reconnected) {
    if (running) {
      return;
    }
    running = true;
    Thread t = new Thread(() -> loop(notified, reconnected), "stock-stream-listen");
    t.setDaemon(true);
    t.start();
    thread = t;
  }

  public synchronized void stop() {
    running = false;
    Thread t = thread;
    if (t != null) {
      t.interrupt();
    }
  }

  private void loop(LongConsumer notified, Runnable reconnected) {
    boolean first = true;
    while (running) {
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
          st.execute("LISTEN " + StockStreamStore.CHANNEL);
        }
        if (!first) {
          reconnected.run();
        }
        first = false;
        PGConnection pg = c.unwrap(PGConnection.class);
        while (running) {
          PGNotification[] ns = pg.getNotifications(1000);
          if (ns == null) {
            continue;
          }
          for (PGNotification n : ns) {
            try {
              notified.accept(Long.parseLong(n.getParameter()));
            } catch (RuntimeException e) {
              log.warn("재고 스트림 알림 처리 실패: {}", e.toString());
            }
          }
        }
      } catch (Exception e) {
        if (!running) {
          return;
        }
        first = false; // 다음 연결은 재접속 — resync
        log.warn("재고 스트림 LISTEN 연결 끊김 — 1초 뒤 다시: {}", e.toString());
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }
}
