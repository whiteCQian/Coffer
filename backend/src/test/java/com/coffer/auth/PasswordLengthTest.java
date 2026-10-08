package com.coffer.auth;

import com.coffer.auth.api.dto.*;
import com.coffer.auth.infrastructure.AccountAuditLogRepository;
import com.coffer.auth.infrastructure.AppUserRepository;
import com.coffer.auth.service.AccountService;
import com.coffer.auth.service.AuthFailureException;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordLengthTest {
    @ParameterizedTest
    @CsvSource({"5,false", "6,true", "16,true", "17,false"})
    void passwordBoundariesMatchAcrossRequestsAndAccountCreation(int length, boolean accepted) {
        String password = "a".repeat(length);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (Object request : List.of(new InitialAdminRequest("token", "admin", password),
                    new CreateUserRequest("alice", password), new ResetPasswordRequest(password),
                    new ChangePasswordRequest("old-password", password), new LoginRequest("alice", password))) {
                assertThat(validator.validate(request).isEmpty()).as(request.getClass().getSimpleName())
                        .isEqualTo(accepted);
            }
        }
        var users = mock(AppUserRepository.class);
        var encoder = mock(PasswordEncoder.class);
        when(users.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(encoder.encode(password)).thenReturn("encoded-password");
        var accounts = new AccountService(users, mock(AccountAuditLogRepository.class), encoder,
                mock(JdbcTemplate.class), mock(FindByIndexNameSessionRepository.class));
        ReflectionTestUtils.setField(accounts, "initialAdminToken", "token");
        if (accepted) {
            assertThat(accounts.createInitialAdmin("token", "admin", password)).isNotNull();
            verify(encoder).encode(password);
        } else {
            assertThatThrownBy(() -> accounts.createInitialAdmin("token", "admin", password))
                    .isInstanceOf(AuthFailureException.class).hasMessageContaining("6–16");
            verifyNoInteractions(encoder);
            verify(users, never()).saveAndFlush(any());
        }
    }
}
