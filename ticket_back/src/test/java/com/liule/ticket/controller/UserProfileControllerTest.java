package com.liule.ticket.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E12-9 公开主页路由与匿名视角的冒烟测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void profileIsPublicAndAnonymousIsNotFollowing() throws Exception {
        mockMvc.perform(get("/user/profile/10001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value("10001"))
                .andExpect(jsonPath("$.data.nickname").value("夜航员"))
                .andExpect(jsonPath("$.data.fans").value(4))
                .andExpect(jsonPath("$.data.followee").value(3))
                .andExpect(jsonPath("$.data.isFollowing").value(false));
    }

    @Test
    void profileOfMissingUserReturnsBusinessFailure() throws Exception {
        mockMvc.perform(get("/user/profile/999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("用户不存在"));
    }
}
