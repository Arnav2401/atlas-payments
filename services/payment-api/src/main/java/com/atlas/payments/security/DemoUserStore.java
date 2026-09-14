package com.atlas.payments.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * <b>A documented simplification, not a real identity directory.</b> This
 * project needs to demonstrate one thing about users: that a role claim in a
 * JWT actually gates a supervisor-only endpoint. It does not need
 * registration, password reset, account lockout, or a user table — none of
 * those are named anywhere in the brief, and building them would be exactly
 * the kind of decoration the brief warns against elsewhere. Two fixed demo
 * accounts, one per role, is the honest minimum that still lets
 * {@code POST /auth/token} be a real authentication check rather than a
 * stub that accepts anything.
 *
 * <p>Passwords are read from environment variables with dev-only defaults —
 * the same pattern as the datasource credentials in application.yaml — not
 * because "analyst-demo-password" is a secret worth protecting, but for
 * consistency with the rule this project actually needs to demonstrate:
 * secrets belong in environment variables, never hardcoded, even when (as
 * here) what is being configured is not sensitive.
 */
@Component
class DemoUserStore {

    private final Map<String, DemoUser> usersByUsername;

    DemoUserStore(
            PasswordEncoder passwordEncoder,
            @Value("${atlas.security.demo-analyst-password:analyst-demo-password}") String analystPassword,
            @Value("${atlas.security.demo-supervisor-password:supervisor-demo-password}") String supervisorPassword) {

        usersByUsername = Map.of(
                "analyst1", new DemoUser("analyst1", passwordEncoder.encode(analystPassword), Role.ANALYST),
                "supervisor1", new DemoUser("supervisor1", passwordEncoder.encode(supervisorPassword), Role.SUPERVISOR));
    }

    Optional<DemoUser> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    record DemoUser(String username, String passwordHash, Role role) {}
}
