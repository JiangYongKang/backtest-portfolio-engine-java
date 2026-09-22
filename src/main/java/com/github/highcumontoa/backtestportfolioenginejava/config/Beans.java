package com.github.highcumontoa.backtestportfolioenginejava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 非属性类 Bean 装配。 */
@Configuration
public class Beans {

    @Bean
    public CostConfig costConfig(EngineProperties properties) {
        return properties.toCostConfig();
    }
}
