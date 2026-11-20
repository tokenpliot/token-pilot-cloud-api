package com.tokenledgercloud.api.domain.rollout.breakglass;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * BreakGlassUsageServiceTest를 실제 Flyway 마이그레이션을 적용한 MySQL에서 그대로 돌린다. (이슈 #19)
 * H2와 MySQL은 json 컬럼을 문자열로 바꾸는 방식이 달라서, metadata LIKE 검색이 MySQL에서도 맞는지 확인하려는 것이다.
 * 비어 있는 일회용 DB에서만 실행한다. 예:
 * BREAK_GLASS_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:33419/bg_test?useSSL=false&allowPublicKeyRetrieval=true'
 * BREAK_GLASS_TEST_MYSQL_PASSWORD=... ./gradlew test --tests '*BreakGlassUsageMySqlIntegrationTest'
 */
@EnabledIfEnvironmentVariable(named = "BREAK_GLASS_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(properties = {
		"spring.datasource.url=${BREAK_GLASS_TEST_MYSQL_URL}",
		"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
		"spring.datasource.username=${BREAK_GLASS_TEST_MYSQL_USER:root}",
		"spring.datasource.password=${BREAK_GLASS_TEST_MYSQL_PASSWORD:}",
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.show-sql=false",
		"spring.flyway.enabled=true",
		"spring.flyway.baseline-on-migrate=false"
})
class BreakGlassUsageMySqlIntegrationTest extends BreakGlassUsageServiceTest {
}
