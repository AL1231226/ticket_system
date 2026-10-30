package com.liule.ticket.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E12-8 关注路由与鉴权的冒烟测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FollowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void followWithoutLoginIsUnauthorized() throws Exception {
        mockMvc.perform(post("/follow/10002/true"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unfollowWithoutLoginIsUnauthorized() throws Exception {
        mockMvc.perform(post("/follow/10002/false"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listFolloweesIsPublic() throws Exception {
        mockMvc.perform(get("/follow/list/10001")
                        .param("type", "followees")
                        .param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.users").isArray());
    }

    @Test
    void listFansIsPublic() throws Exception {
        mockMvc.perform(get("/follow/list/10001")
                        .param("type", "fans")
                        .param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.users").isArray());
    }

    @Test
    void listOfMissingUserReturnsBusinessFailure() throws Exception {
        mockMvc.perform(get("/follow/list/999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("用户不存在"));
    }

    @Test
    void listOfUnknownTypeReturnsBusinessFailure() throws Exception {
        mockMvc.perform(get("/follow/list/10001").param("type", "friends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("type 只能是 followees 或 fans"));
    }

    @Test
    void commonWithoutLoginReturnsBusinessFailure() throws Exception {
        mockMvc.perform(get("/follow/common/10002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("请先登录"));
    }
}
