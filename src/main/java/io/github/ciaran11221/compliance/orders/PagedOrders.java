package io.github.ciaran11221.compliance.orders;

import java.util.List;

public record PagedOrders(List<OrderView> orders, int page, int pageSize, long totalElements) {
}
