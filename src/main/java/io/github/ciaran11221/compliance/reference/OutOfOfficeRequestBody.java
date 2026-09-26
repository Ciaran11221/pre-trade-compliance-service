package io.github.ciaran11221.compliance.reference;

/**
 * PUT /api/staff/{id}/out-of-office's body (spec 3.3/3.6). Both null or both absent clears the
 * out-of-office window; both present sets it (from must be strictly before until); exactly one set
 * is a 400 -- there is no sensible reading of "clear until but keep from".
 */
public record OutOfOfficeRequestBody(String from, String until) {
}
