package io.github.ciaran11221.compliance.orders;

import java.math.BigDecimal;

/**
 * POST /api/orders/{id}/fill's optional request body (issue #21). A missing body, or a body with
 * no price, fills at the order's own reference_price; price, when given, must be strictly positive
 * (OrderService.fill's own validation, not a bean-validation annotation -- same pattern as
 * OrderRequestBody/OrderService.validate).
 */
public record FillRequestBody(BigDecimal price) {
}
