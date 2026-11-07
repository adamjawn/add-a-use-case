package team.projectpulse.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import team.projectpulse.user.PeerEvaluationUser;
import team.projectpulse.user.UserRepository;

/**
 * Turns a verified bearer token into an authentication only while the account it names is still active
 * (FR-SEC-active-account).
 *
 * <p><strong>Why every request.</strong> A token stays valid for its whole lifetime once issued, so checking
 * {@code enabled} only at login would let a student or instructor deactivated mid-session keep working until her
 * token expired. The resource server calls this converter after it has checked the signature and the expiry,
 * so the cost is one primary-key read per authenticated request.
 *
 * <p><strong>How it fails.</strong> A deactivated account raises {@link DisabledException} and a {@code userId}
 * that names no user (deleted since the token was issued) raises {@link BadCredentialsException}. Both are
 * {@code AuthenticationException}s, so the bearer filter hands them to
 * {@link CustomBearerTokenAuthenticationEntryPoint}, and the caller gets a {@code 401} {@code Result}.
 */
@Component
public class ActiveAccountJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter delegate;
    private final UserRepository userRepository;


    public ActiveAccountJwtAuthenticationConverter(JwtAuthenticationConverter jwtAuthenticationConverter, UserRepository userRepository) {
        this.delegate = jwtAuthenticationConverter;
        this.userRepository = userRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Integer userId = readUserId(jwt);
        if (userId == null) {
            throw new BadCredentialsException("The access token names no user.");
        }
        PeerEvaluationUser user = this.userRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("The access token names a user who no longer exists."));
        if (!user.isEnabled()) {
            throw new DisabledException("This account has been deactivated.");
        }
        return this.delegate.convert(jwt);
    }

    private static Integer readUserId(Jwt jwt) {
        Object claim = jwt.getClaim("userId");
        if (claim instanceof Number number) {
            return number.intValue();
        }
        if (claim instanceof String s) {
            try {
                return Integer.valueOf(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

}
