package com.liule.ticket.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E12-5 路由与公开读鉴权的冒烟测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void scrollIsPublic() throws Exception {
        mockMvc.perform(get("/post/scroll").param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.posts").isArray());
    }

    @Test
    void detailOfMissingPostReturnsBusinessFailure() throws Exception {
        mockMvc.perform(get("/post/999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("动态不存在"));
    }

    @Test
    void ofUserRouteIsNotSwallowedByDetailRoute() throws Exception {
        mockMvc.perform(get("/post/of/user/10001").param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.posts").isArray());
    }

    @Test
    void ofShowRouteIsNotSwallowedByDetailRoute() throws Exception {
        mockMvc.perform(get("/post/of/show/1").param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.posts").isArray());
    }

    @Test
    void deleteWithoutLoginIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/post/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void likeWithoutLoginIsUnauthorized() throws Exception {
        mockMvc.perform(put("/post/1/like"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void likedUsersIsPublic() throws Exception {
        mockMvc.perform(get("/post/1/likes").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }
}
