package com.atlas.payments.rings;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M6 (optional): proxies the fraud service's {@code GET /rings} for the ops
 * console, rather than the console calling fraud-service directly. Two
 * reasons, both about keeping this app's existing security posture intact
 * rather than opening a new hole next to it: fraud-service has no
 * authentication of its own (it is only ever meant to be reached from
 * payment-api's own network, same as {@code /score}), and the ops console
 * is already a single-origin client of this API with its own JWT — routing
 * through here means "view fraud rings" answers to the same
 * {@code @PreAuthorize} boundary every other ops-console action does,
 * instead of the browser needing a second, unauthenticated origin to trust.
 */
@RestController
public class RingsController {

    private final RingsClient ringsClient;

    public RingsController(RingsClient ringsClient) {
        this.ringsClient = ringsClient;
    }

    @GetMapping("/rings")
    @PreAuthorize("hasAnyRole('ANALYST', 'SUPERVISOR')")
    public ResponseEntity<RingsResponse> rings(@RequestParam(defaultValue = "15") int topK) {
        return ResponseEntity.ok(ringsClient.fetch(topK));
    }
}
