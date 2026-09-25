package io.github.ciaran11221.compliance.security;

import java.util.function.Supplier;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import io.github.ciaran11221.compliance.reference.StaffRepository;

/**
 * A correctly signed token can still name a "sub" that is not a real member of staff. That gets
 * checked here, once, for every request that reaches it -- not by each controller re-deriving the
 * same rule -- and reported as 403 with the reason "unknown staff member", not a generic
 * access-denied message.
 */
@Component
public class KnownStaffAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

	private final StaffRepository staffRepository;

	public KnownStaffAuthorizationManager(StaffRepository staffRepository) {
		this.staffRepository = staffRepository;
	}

	@Override
	public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
			RequestAuthorizationContext context) {
		Authentication auth = authentication.get();
		if (!(auth instanceof JwtAuthenticationToken jwtAuthentication) || !auth.isAuthenticated()) {
			return new AuthorizationDecision(false);
		}
		if (!staffRepository.existsById(jwtAuthentication.getToken().getSubject())) {
			// A real, signed token with a sub nothing in the staff table recognises -- reported
			// with this exact reason, not AuthorizationFilter's generic "Access Denied" message.
			// AuthorizationFilter calls this method directly (not the verify() default method),
			// so the message has to be thrown from here.
			throw new AccessDeniedException("unknown staff member");
		}
		return new AuthorizationDecision(true);
	}

}
