package ke.chama.modules.auth.presentation.controller;

import jakarta.validation.Valid;
import ke.chama.modules.auth.application.port.in.GetUserProfileUseCase;
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
 * POST /api/v1/auth/logout      public   end this session           204
 * POST /api/v1/auth/logout-all  bearer   end every session          204
 * GET  /api/v1/auth/me          bearer   the caller's profile       200 UserResponse
 * </pre>
 * Logout is public because the refresh token in the body is the proof: it still works when
 * the access token has already expired.
 */
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
    UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return UserResponse.from(registerUser.register(request.toCommand()));
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return AuthResponse.from(login.login(request.toCommand()), clock.instant());
    }

    @PostMapping("/refresh")
    AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return AuthResponse.from(refreshSession.refresh(request.refreshToken()), clock.instant());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshTokenRequest request) {
        logout.logout(request.refreshToken());
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logoutAll() {
        logout.logoutAllSessions(CurrentUser.id());
    }

    @GetMapping("/me")
    UserResponse me() {
        return UserResponse.from(getUserProfile.getProfile(CurrentUser.id()));
    }
}
