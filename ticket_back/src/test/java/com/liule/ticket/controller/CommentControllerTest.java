package com.liule.ticket.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E12-7 评论路由与公开读鉴权的冒烟测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ofPostIsPublic() throws Exception {
        mockMvc.perform(get("/comment/of/post/1").param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.comments").isArray());
    }

    @Test
    void ofPostOfMissingPostReturnsBusinessFailure() throws Exception {
        mockMvc.perform(get("/comment/of/post/999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("动态不存在"));
    }

    @Test
    void publishWithoutLoginIsUnauthorized() throws Exception {
        mockMvc.perform(post("/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":1,\"content\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteWithoutLoginIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/comment/1"))
                .andExpect(status().isUnauthorized());
    }
}
