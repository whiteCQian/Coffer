package com.coffer.config;

import com.coffer.auth.service.CurrentTenantResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages = "com.coffer", repositoryBaseClass = com.coffer.auth.infrastructure.OwnerJpaRepository.class,
        repositoryFactoryBeanClass = com.coffer.auth.infrastructure.OwnerRepositoryFactoryBean.class)
@Configuration
@RequiredArgsConstructor
public class HibernateTenantConfig implements HibernatePropertiesCustomizer {

    private final CurrentTenantResolver tenantResolver;

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put("hibernate.tenant_identifier_resolver", tenantResolver);
    }
}
