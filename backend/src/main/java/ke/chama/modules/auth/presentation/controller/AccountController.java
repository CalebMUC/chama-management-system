package ke.chama.modules.auth.presentation.controller;

import ke.chama.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import ke.chama.modules.auth.application.port.in.ChangePasswordUseCase;
import ke.chama.modules.auth.application.port.in.EmailVerificationUseCase;
import ke.chama.modules.auth.application.port.in.PasswordResetUseCase;
import ke.chama.modules.auth.presentation.dto.request.ChangePasswordRequest;
import ke.chama.modules.auth.presentation.dto.request.EmailRequest;
import ke.chama.modules.auth.presentation.dto.request.ResetPasswordRequest;
import ke.chama.modules.auth.presentation.dto.request.TokenRequest;
import ke.chama.modules.auth.presentation.dto.response.AuthResponse;
import ke.chama.shared.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * <pre>
 * POST /api/v1/auth/email/verify          public  {token}                        200
 * POST /api/v1/auth/email/verify/resend   public  {email}                        202
 * POST /api/v1/auth/password/forgot       public  {email}                        202
 * POST /api/v1/auth/password/reset        public  {token, newPassword}           200
 * POST /api/v1/auth/password/change       bearer  {currentPassword, newPassword} 200 AuthResponse
 * </pre>
 * "resend" and "forgot" always answer 202 whether or not the email is registered, so they
 * cannot be used to discover members' emails.
 */
@Tag(name = "Account", description = "Email verification and passwords")
@RestController
@RequestMapping("/api/v1/auth")
class AccountController {

    private final EmailVerificationUseCase emailVerification;
    private final PasswordResetUseCase passwordReset;
    private final ChangePasswordUseCase changePassword;
    private final Clock clock;

    AccountController(EmailVerificationUseCase emailVerification,
                      PasswordResetUseCase passwordReset,
                      ChangePasswordUseCase changePassword,
                      Clock clock) {
        this.emailVerification = emailVerification;
        this.passwordReset = passwordReset;
        this.changePassword = changePassword;
        this.clock = clock;
    }

    @PostMapping("/email/verify")
    ApiResponse<Void> verifyEmail(@Valid @RequestBody TokenRequest request) {
        emailVerification.verify(request.token());
        return ApiResponse.message("Email verified");
    }

    @PostMapping("/email/verify/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<Void> resendVerification(@Valid @RequestBody EmailRequest request) {
        emailVerification.requestVerification(request.email());
        // Same answer whether or not the account exists, so emails cannot be probed.
        return ApiResponse.message("If that email needs verifying, a new link is on its way");
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<Void> forgotPassword(@Valid @RequestBody EmailRequest request) {
        passwordReset.requestReset(request.email());
        return ApiResponse.message("If an account exists for that email, a reset link is on its way");
    }

    @PostMapping("/password/reset")
    ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordReset.reset(request.token(), request.newPassword());
        return ApiResponse.message("Password reset. Log in with your new password.");
    }

    @PostMapping("/password/change")
    ApiResponse<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return ApiResponse.ok(AuthResponse.from(
                changePassword.changePassword(CurrentUser.id(), request.currentPassword(), request.newPassword()),
                clock.instant()), "Password changed. Your other devices have been logged out.");
    }
}
