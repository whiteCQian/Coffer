package com.coffer.auth.infrastructure;

import com.coffer.auth.domain.TenantOwnedEntity;
import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.auth.service.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import java.io.Serializable;

/** Install authorization on the actual Spring Data proxy, including inherited and derived methods. */
public class OwnerRepositoryFactoryBean<R extends Repository<T, ID>, T, ID extends Serializable>
        extends JpaRepositoryFactoryBean<R, T, ID> {
    private BeanFactory beans;
    public OwnerRepositoryFactoryBean(Class<? extends R> repositoryInterface) { super(repositoryInterface); }
    @Override public void setBeanFactory(BeanFactory beans) { super.setBeanFactory(beans); this.beans = beans; }

    @Override protected RepositoryFactorySupport createRepositoryFactory(EntityManager em) {
        var factory = new JpaRepositoryFactory(em);
        factory.addRepositoryProxyPostProcessor((proxy, information) -> {
            if (!TenantOwnedEntity.class.isAssignableFrom(information.getDomainType())) return;
            proxy.addAdvice(0, (MethodInterceptor) invocation -> {
                if (invocation.getMethod().getDeclaringClass() == Object.class) return invocation.proceed();
                Long owner = beans.getBean(OwnerAuthorization.class).requireOwner();
                var parameters = invocation.getMethod().getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    var param = parameters[i].getAnnotation(org.springframework.data.repository.query.Param.class);
                    if ((param != null && param.value().equals("ownerId")) || parameters[i].getName().equals("ownerId")) {
                        if (!owner.equals(invocation.getArguments()[i])) throw new ResourceNotFoundException();
                    }
                }
                return invocation.proceed();
            });
        });
        return factory;
    }
}
