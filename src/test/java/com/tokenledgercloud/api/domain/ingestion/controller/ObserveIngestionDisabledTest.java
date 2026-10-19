package com.tokenledgercloud.api.domain.ingestion.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** With the default configuration the v2 endpoint does not exist: 404, never the generic 500. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:observe-disabled;DB_CLOSE_DELAY=-1",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.show-sql=false",
	"spring.flyway.enabled=false"
})
class ObserveIngestionDisabledTest {

	@Autowired WebApplicationContext context;
	@Autowired FilterChainProxy securityFilterChain;

	@Test
	void endpointIsNotFoundWhenDisabled() throws Exception {
		MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build()
			.perform(post("/api/v2/observe/events").header("X-API-Key", "any")
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COMMON-404"));
	}
}
