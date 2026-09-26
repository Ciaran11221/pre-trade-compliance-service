package io.github.ciaran11221.compliance.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order-intake API: submit an order, read one back, list a fund's orders, and record a fill or
 * cancel. Route-access.csv carries a row for every mapping here, checked by RouteAccessMatrixTest.
 */
@RestController
public class OrderController {

	private static final String TRADER_ROLE = "hasRole('TRADER')";

	private static final String READ_ROLES = "hasAnyRole('TRADER','SUPERVISOR','COMPLIANCE')";

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	@PostMapping("/api/orders")
	@PreAuthorize(TRADER_ROLE)
	public ResponseEntity<OrderView> submit(@AuthenticationPrincipal Jwt jwt,
			// required = false, same reasoning as LimitChangeController: a missing body must still
			// reach @PreAuthorize before failing, so a disallowed role gets 403 rather than a 400
			// that races the authorization check (RouteAccessMatrixTest calls every route with no
			// body at all).
			@RequestBody(required = false) OrderRequestBody body) {
		OrderView view = orderService.submit(jwt.getSubject(), body);
		return ResponseEntity.status(HttpStatus.CREATED).body(view);
	}

	@GetMapping("/api/orders/{id}")
	@PreAuthorize(READ_ROLES)
	public OrderView getOrder(@PathVariable String id) {
		return orderService.getOrder(parseId(id));
	}

	@GetMapping("/api/funds/{fundId}/orders")
	@PreAuthorize(READ_ROLES)
	public PagedOrders ordersForFund(@PathVariable String fundId, @RequestParam(defaultValue = "0") int page) {
		return orderService.getOrdersForFund(parseId(fundId), page);
	}

	@PostMapping("/api/orders/{id}/fill")
	@PreAuthorize(TRADER_ROLE)
	public OrderView fill(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return orderService.fill(parseId(id), jwt.getSubject());
	}

	@PostMapping("/api/orders/{id}/cancel")
	@PreAuthorize(TRADER_ROLE)
	public OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		return orderService.cancel(parseId(id), jwt.getSubject());
	}

	// A String path variable, parsed by hand, rather than @PathVariable long -- same reasoning as
	// LimitChangeController.parseId: a long parameter fails argument resolution (a 400, before
	// @PreAuthorize ever runs) on any non-numeric id, which is exactly what RouteAccessMatrixTest
	// sends (the literal route-access.csv path, braces included) to prove a disallowed role gets
	// 403 from every route.
	private long parseId(String id) {
		try {
			return Long.parseLong(id);
		}
		catch (NumberFormatException ex) {
			throw OrdersProblems.notFound("no such id: " + id);
		}
	}

}
