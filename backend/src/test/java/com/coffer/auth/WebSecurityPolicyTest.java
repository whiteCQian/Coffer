package com.coffer.auth;
import com.coffer.auth.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
class WebSecurityPolicyTest {
    @Test void passwordsOnlyRequireEightToSixteenCharacters() {
        var policy=new PasswordPolicy();
        for(String valid:new String[]{"12345678","abcdefgh","ABCDEFGH","!!!!!!!!","password1234","QwertyAbc987","Fx8!mP2vFx8!mP2v"})
            assertThatCode(()->policy.validate(valid)).doesNotThrowAnyException();
        for(String invalid:new String[]{"Fx8!mP2","Fx8!mP2vFx8!mP2vx"})
            assertThatThrownBy(()->policy.validate(invalid)).isInstanceOf(AuthFailureException.class).hasMessageContaining("8–16");
        assertThat(policy.minimumLength()).isEqualTo(8);
        assertThat(policy.strongRequired()).isFalse();
    }
    @Test void absoluteSessionExpiryInvalidatesBeforeProtectedRequestAndClearsCachedAuthentication() throws Exception {
        var session=new MockHttpSession(); org.springframework.test.util.ReflectionTestUtils.setField(session,"creationTime",System.currentTimeMillis()-Duration.ofHours(9).toMillis());
        var request=new MockHttpServletRequest(); request.setSession(session); var response=new MockHttpServletResponse();
        var chain=new MockFilterChain(); new SessionLifetimeFilter(true,new ObjectMapper()).doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(401); assertThat(session.isInvalid()).isTrue(); assertThat(chain.getRequest()).isNull();
    }
}
