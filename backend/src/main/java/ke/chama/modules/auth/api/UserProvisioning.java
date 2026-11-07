package ke.chama.modules.auth.api;

/**
 * Account creation for other modules, e.g. the first administrator of a newly onboarded chama.
 * The account is created in the current tenant, must run inside the caller's transaction, and is
 * sent an email verification link.
 */
public interface UserProvisioning {

    UserSummary createAccount(NewAccount account);

    /**
     * An account for someone who has not chosen a password yet, e.g. a member imported from the
     * chama's spreadsheets. Instead of a verification email they get a welcome email with a link
     * to choose their password, which also confirms their email address.
     */
    UserSummary importAccount(ImportedAccount account, String chamaName);

    record ImportedAccount(String email, String phoneNumber, String firstName, String lastName) {
    }

    /** Password rules are the same as for self-registration. */
    record NewAccount(String email, String phoneNumber, String password, String firstName, String lastName) {

        @Override
        public String toString() {
            return "NewAccount[email=" + email + ", password=***]";
        }
    }
}
