package com.acme.salary.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link CompensationProperties}. */
@Configuration
@EnableConfigurationProperties(CompensationProperties.class)
public class CompensationConfig {
}
