package com.iterationboard.auth;

import com.iterationboard.config.CurrentUser;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final CaptchaService captchaService;
    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(CaptchaService captchaService, AuthService authService, CurrentUser currentUser) {
        this.captchaService = captchaService;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @GetMapping("/captcha")
    public Map<String, String> captcha(HttpServletRequest request) {
        CaptchaService.Issued issued = captchaService.issue(request.getSession(true));
        return Map.of("captchaId", issued.captchaId(), "imageSvg", issued.imageSvg());
    }

    public record RegisterRequest(@NotBlank String loginName, @NotBlank String displayName,
                                  @NotBlank String password, String captchaId, String captchaCode) {}
    public record LoginRequest(@NotBlank String loginName, @NotBlank String password,
                               String captchaId, String captchaCode) {}
    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}

    @PostMapping("/register")
    public ResponseEntity<Dtos.UserDto> register(@RequestBody RegisterRequest req,
                                                 HttpServletRequest request, HttpServletResponse response) {
        AppUser u = authService.register(req.loginName(), req.displayName(), req.password(),
                req.captchaId(), req.captchaCode(), request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(Dtos.UserDto.of(u));
    }

    @PostMapping("/login")
    public Dtos.UserDto login(@RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        AppUser u = authService.login(req.loginName(), req.password(), req.captchaId(), req.captchaCode(), request, response);
        return Dtos.UserDto.of(u);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Dtos.UserDto me() {
        return Dtos.UserDto.of(currentUser.require());
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest req, HttpServletRequest request) {
        authService.changePassword(currentUser.require(), req.oldPassword(), req.newPassword(), request);
        return ResponseEntity.noContent().build();
    }
}
