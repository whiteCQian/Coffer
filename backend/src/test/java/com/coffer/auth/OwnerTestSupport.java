package com.coffer.auth;

import com.coffer.auth.domain.*;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.BeforeTransaction;

/** Explicit normal-user fixture for formerly anonymous business regression tests. */
public abstract class OwnerTestSupport {
    @Autowired AppUserRepository fixtureUsers;
    @MockitoBean io.minio.MinioClient fixtureMinioClient;

    @BeforeTransaction
    @BeforeEach
    public void establishOwner() {
        var user = fixtureUsers.findByUsername("business-test-owner").orElseGet(() ->
                fixtureUsers.saveAndFlush(new AppUser("business-test-owner", "unused", AuthRole.USER)));
        TenantContext.set(user.getId());
    }

    @AfterEach public void clearOwner() { TenantContext.clear(); }
    protected static String ownerPath(String suffix) { return "users/" + TenantContext.requireOwnerId() + "/" + suffix; }
}
