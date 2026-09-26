package io.github.ciaran11221.compliance.orders;

/**
 * POST /api/orders's request body. fundId is the fund's numeric primary key (fund.id), the same
 * identifier GET /api/funds/{fundId}/orders takes as a path variable -- one meaning for "fundId"
 * across the whole orders API, rather than mixing it with fund.code (used only for display, e.g.
 * "HGF"). side is "BUY" or "SELL"; ticker matches security.ticker.
 */
public record OrderRequestBody(String clientOrderId, Long fundId, String side, String ticker, Long quantity) {
}
