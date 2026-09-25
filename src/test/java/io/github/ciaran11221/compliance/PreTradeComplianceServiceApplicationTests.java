package io.github.ciaran11221.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import io.github.ciaran11221.compliance.support.TestcontainersConfig;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class PreTradeComplianceServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
