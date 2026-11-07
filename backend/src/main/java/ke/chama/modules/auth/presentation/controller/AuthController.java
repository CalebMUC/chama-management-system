package ke.chama.modules.auth.presentation.controller;

import ke.chama.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import ke.chama.modules.auth.application.port.in.GetUserProfileUseCase;
import ke.chama.modules.auth.application.port.in.LoginCommand;
import ke.chama.modules.auth.application.port.in.LoginUseCase;
import ke.chama.modules.auth.application.port.in.LogoutUseCase;
import ke.chama.modules.auth.application.port.in.RefreshSessionUseCase;
import ke.chama.modules.auth.application.port.in.RegisterUserUseCase;
import ke.chama.modules.auth.presentation.dto.request.LoginRequest;
import ke.chama.modules.auth.presentation.dto.request.RefreshTokenRequest;
import ke.chama.modules.auth.presentation.dto.request.RegisterRequest;
import ke.chama.modules.auth.presentation.dto.response.AuthResponse;
import ke.chama.modules.auth.presentation.dto.response.UserResponse;
import ke.chama.shared.security.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * <pre>
 * POST /api/v1/auth/register    public   create an account          201 UserResponse
 * POST /api/v1/auth/login       public   start a session            200 AuthResponse
 * POST /api/v1/auth/refresh     public   rotate the refresh token   200 AuthResponse
 * POST /api/v1/auth/logout      public   end this session           200
 * POST /api/v1/auth/logout-all  bearer   end every session          200
 * GET  /api/v1/auth/me          bearer   the caller's profile       200 UserResponse
 * </pre>
 * Logout is public because the refresh token in the body is the proof: it still works when
 * the access token has already expired.
 */
@Tag(name = "Auth", description = "Register, log in, refresh and end sessions, current user")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final RegisterUserUseCase registerUser;
    private final LoginUseCase login;
    private final RefreshSessionUseCase refreshSession;
    private final LogoutUseCase logout;
    private final GetUserProfileUseCase getUserProfile;
    private final Clock clock;

    AuthController(RegisterUserUseCase registerUser,
                   LoginUseCase login,
                   RefreshSessionUseCase refreshSession,
                   LogoutUseCase logout,
                   GetUserProfileUseCase getUserProfile,
                   Clock clock) {
        this.registerUser = registerUser;
        this.login = login;
        this.refreshSession = refreshSession;
        this.logout = logout;
        this.getUserProfile = getUserProfile;
        this.clock = clock;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(UserResponse.from(registerUser.register(request.toCommand())),
                "Account created. Check your email for a link to verify your address.");
    }

    /**
     * The client IP is recorded with the attempt. Behind a load balancer, set
     * {@code server.forward-headers-strategy: framework} so this is the real client address
     * (X-Forwarded-For) rather than the proxy's.
     */
    @PostMapping("/login")
    ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        LoginCommand command = new LoginCommand(request.email(), request.password(),
                http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
        return ApiResponse.ok(AuthResponse.from(login.login(command), clock.instant()), "Logged in");
    }

    @PostMapping("/refresh")
    ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(AuthResponse.from(refreshSession.refresh(request.refreshToken()), clock.instant()),
                "Session refreshed");
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        logout.logout(request.refreshToken());
        return ApiResponse.message("Logged out");
    }

    @PostMapping("/logout-all")
    ApiResponse<Void> logoutAll() {
        logout.logoutAllSessions(CurrentUser.id());
        return ApiResponse.message("Logged out of all devices");
    }

    @GetMapping("/me")
    ApiResponse<UserResponse> me() {
        return ApiResponse.ok(UserResponse.from(getUserProfile.getProfile(CurrentUser.id())));
    }
}
