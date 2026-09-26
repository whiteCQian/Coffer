package com.coffer.auth.service;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Runs before transactions open their tenant-bound EntityManager. */
@Aspect
@Component
@Order(-100)
@RequiredArgsConstructor
public class OwnerAuthorizationAspect {
    private final OwnerAuthorization authorization;

    @Around("execution(public * *(..)) && (@within(com.coffer.auth.service.OwnerOnly) || @annotation(com.coffer.auth.service.OwnerOnly))")
    public Object authorize(ProceedingJoinPoint call) throws Throwable {
        Long owner = authorization.requireOwner();
        for (Object argument : call.getArgs()) checkOwnedArgument(argument, owner);
        var method = ((org.aspectj.lang.reflect.MethodSignature) call.getSignature()).getMethod();
        var parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            var param = parameters[i].getAnnotation(org.springframework.data.repository.query.Param.class);
            if ((param != null && param.value().equals("ownerId")) || parameters[i].getName().equals("ownerId")) {
                if (!owner.equals(call.getArgs()[i])) throw new ResourceNotFoundException();
            }
        }
        try (var lease = PrivateWorkspaceGate.enter(owner)) { return call.proceed(); }
    }

    private void checkOwnedArgument(Object argument, Long owner) {
        if (argument instanceof com.coffer.auth.domain.TenantOwnedEntity entity
                && !owner.equals(entity.getOwnerId())) throw new ResourceNotFoundException();
        if (argument instanceof Iterable<?> values) values.forEach(value -> checkOwnedArgument(value, owner));
    }
}
