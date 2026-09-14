package com.atlas.payments.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /auth/token} — the one unauthenticated endpoint (see
 * SecurityConfig). Demo credentials only; see {@link DemoUserStore}.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final DemoUserStore users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(DemoUserStore users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public record TokenRequest(String username, String password) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {}

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@RequestBody TokenRequest request) {
        DemoUserStore.DemoUser user = users.findByUsername(request.username()).orElse(null);

        // A constant-shape failure whether the username does not exist or the
        // password is wrong - checked with the same passwordEncoder.matches
        // call either way (against a fixed dummy hash when the user is
        // absent), so a 401's timing does not itself reveal which username in
        // the demo store exists. Not exhaustive constant-time hardening - a
        // real auth server needs more - but "wrong password" and "no such
        // user" produce the identical response and roughly the identical
        // amount of work either way, rather than one being visibly cheaper.
        boolean valid = user != null && passwordEncoder.matches(request.password(), user.passwordHash());
        if (!valid) {
            passwordEncoder.matches(request.password(), NO_SUCH_USER_HASH);
            throw new BadCredentialsException("invalid username or password");
        }

        String token = jwtService.issue(user.username(), user.role());
        return ResponseEntity.ok(new TokenResponse(token, "Bearer", jwtService.ttlSeconds()));
    }

    // A fixed, precomputed BCrypt hash of an arbitrary string, matched
    // against on every failed lookup - see the comment above.
    private static final String NO_SUCH_USER_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5c5FEqQD2ZWQ1WBOn8m5wpQnHbhOe";
}
