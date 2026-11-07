package com.pagelens.core.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Entry point for core beans. App modules put their {@code @SpringBootApplication} class in the
 * {@code com.pagelens} package so component scanning covers {@code com.pagelens.core}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PageLensProperties.class, MetaProperties.class, HttpProperties.class,
        SyncProperties.class, MetricCatalog.class, PublishProperties.class, BackupProperties.class,
        WindsorProperties.class})
public class CoreConfiguration {

    /** UTC clock; tests replace it to control "now". */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
