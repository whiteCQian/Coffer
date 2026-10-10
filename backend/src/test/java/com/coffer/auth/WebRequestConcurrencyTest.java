package com.coffer.auth;
import com.coffer.auth.domain.AuthRole;
import com.coffer.auth.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
class WebRequestConcurrencyTest {
    static void as(long owner) {
        var user=new AuthPrincipal(owner,"fixture",null,AuthRole.USER,true);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user,null,user.getAuthorities()));
    }
    static MockHttpServletRequest upload() {
        var request=new MockHttpServletRequest("POST","/api/files/upload"); request.setContentType("multipart/form-data;boundary=fixture");return request;
    }
    @Test void sameAccountParallelUploadIsRejectedBeforeParsingAndPermitRecoversAfterFailure() throws Exception {
        var filter=new WebRequestConcurrencyFilter(true,new ObjectMapper());
        var started=new CountDownLatch(1); var finish=new CountDownLatch(1); var worker=Executors.newSingleThreadExecutor();
        try {
            var first=worker.submit(()->{as(1); try{filter.doFilter(upload(),new MockHttpServletResponse(),(r,s)->{
                started.countDown(); try{finish.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                throw new jakarta.servlet.ServletException("fixture failure");
            });}catch(Exception expected){}finally{SecurityContextHolder.clearContext();}});
            assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();as(1);
            var second=new MockHttpServletResponse();var deniedChain=new MockFilterChain();filter.doFilter(upload(),second,deniedChain);
            assertThat(second.getStatus()).isEqualTo(429);assertThat(deniedChain.getRequest()).isNull();assertThat(second.getHeader("Retry-After")).isEqualTo("30");
            as(2);var otherChain=new MockFilterChain();filter.doFilter(upload(),new MockHttpServletResponse(),otherChain);assertThat(otherChain.getRequest()).isNotNull();
            finish.countDown();first.get(3,TimeUnit.SECONDS);as(1);var finalChain=new MockFilterChain();filter.doFilter(upload(),new MockHttpServletResponse(),finalChain);assertThat(finalChain.getRequest()).isNotNull();
        } finally {finish.countDown();worker.shutdownNow();SecurityContextHolder.clearContext();}
    }
    @Test void anonymousMultipartNeverReachesCsrfOrServletBodyParsing() throws Exception {
        SecurityContextHolder.clearContext();var response=new MockHttpServletResponse();var chain=new MockFilterChain();
        new WebRequestConcurrencyFilter(true,new ObjectMapper()).doFilter(upload(),response,chain);
        assertThat(response.getStatus()).isEqualTo(401);assertThat(chain.getRequest()).isNull();
    }
    @Test void exportingIsExclusiveWithOtherMutationsAndRecoversAfterCompletion() throws Exception {
        var filter=new WebRequestConcurrencyFilter(true,new ObjectMapper());var started=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var worker=Executors.newSingleThreadExecutor();
        try {
            var export=worker.submit(()->{as(1);try{filter.doFilter(new MockHttpServletRequest("GET","/api/privacy/export"),new MockHttpServletResponse(),(r,s)->{
                started.countDown();try{finish.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            });}catch(Exception failure){throw new RuntimeException(failure);}finally{SecurityContextHolder.clearContext();}});
            assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();as(2);
            var response=new MockHttpServletResponse();filter.doFilter(upload(),response,new MockFilterChain());assertThat(response.getStatus()).isEqualTo(429);
            finish.countDown();export.get(3,TimeUnit.SECONDS);var chain=new MockFilterChain();filter.doFilter(upload(),new MockHttpServletResponse(),chain);assertThat(chain.getRequest()).isNotNull();
        } finally{finish.countDown();worker.shutdownNow();SecurityContextHolder.clearContext();}
    }
}
