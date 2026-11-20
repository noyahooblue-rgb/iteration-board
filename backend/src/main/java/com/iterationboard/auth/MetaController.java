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
    private final com.iterationboard.admin.VersionInfo versionInfo;

    public MetaController(@Value("${app.registration-open:true}") boolean registrationOpen,
                          @Value("${app.demo-mode:false}") boolean demoMode,
                          com.iterationboard.admin.VersionInfo versionInfo) {
        this.registrationOpen = registrationOpen;
        this.demoMode = demoMode;
        this.versionInfo = versionInfo;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("registrationOpen", registrationOpen);
        m.put("demoMode", demoMode);
        m.put("version", versionInfo.version());
        m.put("buildDate", versionInfo.buildDate());
        return m;
    }
}
