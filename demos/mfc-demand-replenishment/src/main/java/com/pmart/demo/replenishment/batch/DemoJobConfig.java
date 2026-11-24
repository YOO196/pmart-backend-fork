package com.pmart.demo.replenishment.batch;

import java.util.function.Consumer;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 작업 mfcDemandReplenishmentJob: prepare → sales → forecast → recommend → backtest → report. 실패한 단계부터 같은 매개변수로
 * 다시 시작할 수 있다(각 단계는 자기 결과를 지우고 다시 쓴다).
 */
@Configuration
public class DemoJobConfig {

  public static final String JOB_NAME = "mfcDemandReplenishmentJob";

  @Bean
  Job mfcDemandReplenishmentJob(JobRepository repo, PlatformTransactionManager tx, DemoSteps steps) {
    return new JobBuilder(JOB_NAME, repo)
        .start(step("prepare", repo, tx, steps::prepare))
        .next(step("sales", repo, tx, steps::salesStep))
        .next(step("forecast", repo, tx, steps::forecastStep))
        .next(step("recommend", repo, tx, steps::recommendStep))
        .next(step("backtest", repo, tx, steps::backtestStep))
        .next(step("report", repo, tx, steps::reportStep))
        .build();
  }

  private static Step step(
      String name, JobRepository repo, PlatformTransactionManager tx, Consumer<StepExecution> body) {
    return new StepBuilder(name, repo)
        .tasklet(
            (contribution, chunk) -> {
              body.accept(chunk.getStepContext().getStepExecution());
              return RepeatStatus.FINISHED;
            },
            tx)
        .build();
  }
}
