package com.github.highcumontoa.backtestportfolioenginejava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 统一时钟，便于测试对"当前时间"的控制。 */
@Configuration
public class ClockBean {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
