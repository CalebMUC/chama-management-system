package ke.chama.modules.auth.presentation.controller;

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
 * POST /api/v1/auth/email/verify          public  {token}                        204
 * POST /api/v1/auth/email/verify/resend   public  {email}                        202
 * POST /api/v1/auth/password/forgot       public  {email}                        202
 * POST /api/v1/auth/password/reset        public  {token, newPassword}           204
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyEmail(@Valid @RequestBody TokenRequest request) {
        emailVerification.verify(request.token());
    }

    @PostMapping("/email/verify/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resendVerification(@Valid @RequestBody EmailRequest request) {
        emailVerification.requestVerification(request.email());
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgotPassword(@Valid @RequestBody EmailRequest request) {
        passwordReset.requestReset(request.email());
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordReset.reset(request.token(), request.newPassword());
    }

    @PostMapping("/password/change")
    AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return AuthResponse.from(
                changePassword.changePassword(CurrentUser.id(), request.currentPassword(), request.newPassword()),
                clock.instant());
    }
}
