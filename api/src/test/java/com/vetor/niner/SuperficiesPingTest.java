package com.vetor.niner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova que as três superfícies roteiam (ADR-007) e que a cadeia de {@code /api/v1}
 * estabelece o {@link com.vetor.niner.comum.tenant.TenantContext} a partir do claim
 * {@code tid} do JWT (P8, §3.1.1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SuperficiesPingTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void publicoPingResponde() throws Exception {
        mockMvc.perform(get("/api/publico/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.superficie").value("publico"));
    }

    @Test
    void adminPingResponde() throws Exception {
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.superficie").value("admin"));
    }

    @Test
    void actuatorHealthResponde() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void v1SemTokenEhNaoAutorizado() throws Exception {
        mockMvc.perform(get("/api/v1/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void v1ComTidNoJwtPropagaTenant() throws Exception {
        mockMvc.perform(get("/api/v1/ping").with(jwt().jwt(j -> j.claim("tid", 42L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id_tenant").value(42));
    }

    @Test
    void rotaForaDasSuperficiesEhNegada() throws Exception {
        mockMvc.perform(get("/qualquer-outra-coisa"))
                .andExpect(status().isForbidden());
    }
}
