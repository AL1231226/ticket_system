package com.liule.ticket.utils;

import com.liule.ticket.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityWriteInterceptorTest {

    private final CommunityWriteInterceptor interceptor = new CommunityWriteInterceptor();

    @AfterEach
    void clean() {
        UserHolder.removeUser();
    }

    @Test
    void getIsPublic() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/post/scroll");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void postWithoutLoginReturns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/post");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void postWithLoginPasses() throws Exception {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/post");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }
}
