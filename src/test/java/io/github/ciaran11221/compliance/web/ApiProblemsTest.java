package io.github.ciaran11221.compliance.web;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.ErrorResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test that ProblemDetail.setProperty("errors", list) actually serializes the errors array to JSON.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApiProblemsTest {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void badRequestSerializesErrorsAsTopLevelArray() throws Exception {
		List<ApiProblems.FieldError> errors = List.of(
			new ApiProblems.FieldError("field1", "error 1"),
			new ApiProblems.FieldError("field2", "error 2")
		);

		ErrorResponseException ex = ApiProblems.badRequest(errors);

		// Serialize the problem detail to JSON
		String json = objectMapper.writeValueAsString(ex.getBody());
		JsonNode node = objectMapper.readTree(json);

		assertThat(node.has("errors")).isTrue();
		JsonNode errorsNode = node.get("errors");
		assertThat(errorsNode.isArray()).isTrue();
		assertThat(errorsNode.size()).isEqualTo(2);

		JsonNode firstError = errorsNode.get(0);
		assertThat(firstError.get("field").asText()).isEqualTo("field1");
		assertThat(firstError.get("message").asText()).isEqualTo("error 1");

		JsonNode secondError = errorsNode.get(1);
		assertThat(secondError.get("field").asText()).isEqualTo("field2");
		assertThat(secondError.get("message").asText()).isEqualTo("error 2");
	}

}
