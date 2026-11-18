package com.iterationboard.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MetaController {
    private final boolean registrationOpen;
    private final boolean demoMode;

    public MetaController(@Value("${app.registration-open:true}") boolean registrationOpen,
                          @Value("${app.demo-mode:false}") boolean demoMode) {
        this.registrationOpen = registrationOpen;
        this.demoMode = demoMode;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return Map.of("registrationOpen", registrationOpen, "demoMode", demoMode);
    }
}
