package com.campus.library;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.library.service.SeedDatasetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 回归测试（BUG-05）：关闭实验时钟开关后，借还业务必须使用真实日期而非 clock_setting.sim_date。
 */
@SpringBootTest(properties = "app.experimental-clock-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExperimentalClockOffTest {

    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SeedDatasetService seedDatasetService;

    @BeforeEach
    void resetDataset() {
        seedDatasetService.seedDataset(true);
    }

    @Test
    void checkoutUsesRealDateWhenExperimentalClockIsDisabled() throws Exception {
        MvcResult loginRes = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginName", "a4",
                                "password", "CampusLib#2026"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession a4Session = (MockHttpSession) loginRes.getRequest().getSession(false);

        LocalDate realToday = LocalDate.now(SHANGHAI_ZONE);
        mockMvc.perform(post("/api/circulation/checkout")
                        .session(a4Session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookId", 115,
                                "requestId", UUID.randomUUID().toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.borrowed_on").value(realToday.toString()))
                .andExpect(jsonPath("$.due_on").value(realToday.plusDays(14).toString()));
    }
}
