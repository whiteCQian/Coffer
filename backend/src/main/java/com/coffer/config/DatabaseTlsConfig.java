package com.coffer.config;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import com.coffer.deployment.DatabaseTls;
@Configuration @Profile("prod") @ConditionalOnProperty(name="coffer.database.tls.enabled", havingValue="true")
public class DatabaseTlsConfig {
    @Bean static BeanPostProcessor databaseTlsProperties() {
        return new BeanPostProcessor() {
            @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                if (bean instanceof HikariDataSource source) DatabaseTls.configure(source);
                return bean;
            }
        };
    }
}
