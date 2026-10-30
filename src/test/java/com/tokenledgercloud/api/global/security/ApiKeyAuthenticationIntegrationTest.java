package com.tokenledgercloud.api.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.tokenledgercloud.api.domain.apikey.ApiKey;
import com.tokenledgercloud.api.domain.apikey.ApiKeyRepository;
import com.tokenledgercloud.api.domain.member.entity.Member;
import com.tokenledgercloud.api.domain.member.entity.Role;
import com.tokenledgercloud.api.domain.member.repository.MemberRepository;
import com.tokenledgercloud.api.global.util.HashingUtil;

/**
 * Deliberately NOT @Transactional: a test transaction would keep the session open across the filter
 * and hide lazy-loading failures that happen in a real request.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:member-api-key-auth;DB_CLOSE_DELAY=-1",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=false"
})
class ApiKeyAuthenticationIntegrationTest {
	private static final String RAW_KEY = "tk-member-api-key-auth-test";

	@Autowired WebApplicationContext context;
	@Autowired FilterChainProxy securityFilterChain;
	@Autowired MemberRepository members;
	@Autowired ApiKeyRepository apiKeys;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
		Member member = members.save(Member.builder()
			.email("api-key-user@example.com").name("API Key User").role(Role.USER).provider("local").build());
		apiKeys.save(ApiKey.builder()
			.hashedKey(HashingUtil.sha256(RAW_KEY)).displayKey("tk-mem...test").member(member).build());
	}

	@AfterEach
	void tearDown() {
		apiKeys.deleteAll();
		members.deleteAll();
	}

	@Test
	void memberApiKeyAuthenticatesOutsideAnOpenSession() throws Exception {
		mvc.perform(get("/api/me").header("X-API-KEY", RAW_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.email").value("api-key-user@example.com"));
	}
}
