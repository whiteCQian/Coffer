package com.coffer.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class DesktopLaunchFilterTest {
    @Test void tokenGateRejectsOrdinaryWebRequestsAndStillDelegatesAuthenticatedRequestsToSpringSecurity() throws Exception {
        var filter=new DesktopLaunchFilter("a".repeat(64),true);var called=new AtomicInteger();
        var denied=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET","/api/files"),denied,(request,response)->called.incrementAndGet());
        assertThat(denied.getStatus()).isEqualTo(403);assertThat(called.get()).isZero();
        var wrong=new MockHttpServletRequest("POST","/api/auth/setup");wrong.addHeader("X-Coffer-Desktop-Token","b".repeat(64));var wrongResponse=new MockHttpServletResponse();
        filter.doFilter(wrong,wrongResponse,(request,response)->called.incrementAndGet());assertThat(wrongResponse.getStatus()).isEqualTo(403);
        var valid=new MockHttpServletRequest("GET","/api/files");valid.addHeader("X-Coffer-Desktop-Token","a".repeat(64));
        filter.doFilter(valid,new MockHttpServletResponse(),(request,response)->called.incrementAndGet());assertThat(called.get()).isEqualTo(1);
    }
    @Test void shellCannotStartWithAnEmptyOrMalformedLaunchToken() {
        assertThatThrownBy(()->new DesktopLaunchFilter("",true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new DesktopLaunchFilter("weak",true)).isInstanceOf(IllegalStateException.class);
    }
}
