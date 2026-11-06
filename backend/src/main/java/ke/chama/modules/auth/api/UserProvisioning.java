package ke.chama.modules.auth.api;

/**
 * Account creation for other modules, e.g. the first administrator of a newly onboarded chama.
 * The account is created in the current tenant, must run inside the caller's transaction, and is
 * sent an email verification link.
 */
public interface UserProvisioning {

    UserSummary createAccount(NewAccount account);

    /** Password rules are the same as for self-registration. */
    record NewAccount(String email, String phoneNumber, String password, String firstName, String lastName) {

        @Override
        public String toString() {
            return "NewAccount[email=" + email + ", password=***]";
        }
    }
}
