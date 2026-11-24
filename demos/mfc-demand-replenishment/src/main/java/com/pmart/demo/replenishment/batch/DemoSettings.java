package com.pmart.demo.replenishment.batch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 작업 매개변수가 아닌 설정(같은 입력의 재실행 판정에 넣지 않는 값). 명령줄 {@code --demo.ma-window=14} 처럼 바꾼다.
 */
@Component
public class DemoSettings {

  public static final String APP_VERSION = "0.1.0";

  final int maWindow;
  final int forecastHorizonDays;
  final double staleGapMultiplier;
  final int lookbackDays;
  final boolean backtest;
  final int backtestStepDays;
  final int backtestLeadDays;
  final String outputDir;

  public DemoSettings(
      @Value("${demo.ma-window:28}") int maWindow,
      @Value("${demo.forecast-horizon-days:14}") int forecastHorizonDays,
      @Value("${demo.stale-gap-multiplier:2.0}") double staleGapMultiplier,
      @Value("${demo.lookback-days:365}") int lookbackDays,
      @Value("${demo.backtest:true}") boolean backtest,
      @Value("${demo.backtest-step-days:7}") int backtestStepDays,
      @Value("${demo.backtest-lead-days:2}") int backtestLeadDays,
      @Value("${demo.output-dir:build/demo-out}") String outputDir) {
    this.maWindow = maWindow;
    this.forecastHorizonDays = forecastHorizonDays;
    this.staleGapMultiplier = staleGapMultiplier;
    this.lookbackDays = lookbackDays;
    this.backtest = backtest;
    this.backtestStepDays = backtestStepDays;
    this.backtestLeadDays = backtestLeadDays;
    this.outputDir = outputDir;
  }
}
