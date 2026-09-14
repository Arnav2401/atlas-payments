package com.atlas.payments.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

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
