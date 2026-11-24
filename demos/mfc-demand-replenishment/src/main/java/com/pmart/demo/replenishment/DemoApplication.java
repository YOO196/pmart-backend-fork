package com.pmart.demo.replenishment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** MFC 수요예측·보충추천 데모 배치. 실행 방법은 README.md. */
@SpringBootApplication
public class DemoApplication {

  public static void main(String[] args) {
    System.exit(SpringApplication.exit(SpringApplication.run(DemoApplication.class, args)));
  }
}
