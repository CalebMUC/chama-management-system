package ke.chama.modules.auth.application.port.in;

/** {@code ipAddress} and {@code userAgent} are recorded with the attempt; both may be null. */
public record LoginCommand(String email, String password, String ipAddress, String userAgent) {

    public LoginCommand(String email, String password) {
        this(email, password, null, null);
    }

    @Override
    public String toString() {
        return "LoginCommand[email=" + email + ", password=***, ipAddress=" + ipAddress + "]";
    }
}
