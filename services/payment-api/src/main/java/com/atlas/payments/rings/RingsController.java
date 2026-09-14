package com.atlas.payments.rings;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
