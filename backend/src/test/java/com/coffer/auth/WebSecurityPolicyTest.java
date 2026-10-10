package com.coffer.auth;
import com.coffer.auth.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
class WebSecurityPolicyTest {
    @Test void productionRejectsWeakPasswordsWhileLoginCompatibilityAndDesktopPolicyRemain() {
        var environment=new MockEnvironment(); environment.setActiveProfiles("prod");
        var policy=new PasswordPolicy(environment);
        for(String weak:new String[]{"123456","aaaaaaaaaaaa","password1234","Password1234","QwertyAbc987"})
            assertThatThrownBy(()->policy.validate(weak)).isInstanceOf(AuthFailureException.class);
        policy.validate("Fx8!mP2v7Q9s");
        assertThat(policy.minimumLength()).isEqualTo(12);
        new PasswordPolicy(new MockEnvironment()).validate("abcdef");
    }
    @Test void absoluteSessionExpiryInvalidatesBeforeProtectedRequestAndClearsCachedAuthentication() throws Exception {
        var session=new MockHttpSession(); org.springframework.test.util.ReflectionTestUtils.setField(session,"creationTime",System.currentTimeMillis()-Duration.ofHours(9).toMillis());
        var request=new MockHttpServletRequest(); request.setSession(session); var response=new MockHttpServletResponse();
        var chain=new MockFilterChain(); new SessionLifetimeFilter(true,new ObjectMapper()).doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(401); assertThat(session.isInvalid()).isTrue(); assertThat(chain.getRequest()).isNull();
    }
}
