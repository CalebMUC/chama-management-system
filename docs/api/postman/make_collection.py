"""Generates Chama-API.postman_collection.json (Postman collection v2.1) for every API endpoint.

Edit the folders below when endpoints change, then run from this directory:

    python make_collection.py Chama-API.postman_collection.json
"""
import json
import sys
import uuid

OUT = sys.argv[1] if len(sys.argv) > 1 else "Chama-API.postman_collection.json"


def script(lines):
    return [{"listen": "test", "script": {"type": "text/javascript", "exec": lines}}]


def save(var, expr):
    """Test script: store a value from the response in a collection variable."""
    return [
        "const j = pm.response.json();",
        "if (j.success) {",
        f"  const v = (() => {{ try {{ return {expr}; }} catch (e) {{ return undefined; }} }})();",
        f"  if (v !== undefined && v !== null) pm.collectionVariables.set('{var}', v);",
        "}",
    ]


AUTH = {
    "official": None,  # inherits the collection's bearer {{officialToken}}
    "member": {"type": "bearer", "bearer": [{"key": "token", "value": "{{memberToken}}", "type": "string"}]},
    "platform": {"type": "bearer", "bearer": [{"key": "token", "value": "{{platformToken}}", "type": "string"}]},
    "none": {"type": "noauth"},
}


def req(name, method, path, body=None, auth="official", query=None, tests=None, desc=None, chama_header=False,
        form=None):
    segments = [s for s in path.split("/") if s]
    url = {"raw": "{{baseUrl}}/" + "/".join(segments), "host": ["{{baseUrl}}"], "path": segments}
    if query:
        url["query"] = [{"key": k, "value": v, "disabled": dis, **({"description": d} if d else {})}
                        for k, v, dis, d in query]
        url["raw"] += "?" + "&".join(f"{k}={v}" for k, v, dis, _ in query if not dis)
    headers = []
    if body is not None:
        headers.append({"key": "Content-Type", "value": "application/json"})
    if chama_header:
        headers.append({"key": "X-Chama-Code", "value": "{{chamaCode}}",
                        "description": "Which chama this public request is for"})
    r = {"method": method, "header": headers, "url": url}
    if body is not None:
        r["body"] = {"mode": "raw", "raw": json.dumps(body, indent=2), "options": {"raw": {"language": "json"}}}
    if form is not None:
        r["body"] = {"mode": "formdata", "formdata": form}
    if AUTH[auth] is not None:
        r["auth"] = AUTH[auth]
    if desc:
        r["description"] = desc
    item = {"name": name, "request": r}
    if tests:
        item["event"] = script(tests)
    return item


def folder(name, items, desc=None):
    f = {"name": name, "item": items}
    if desc:
        f["description"] = desc
    return f


def q(key, value="", disabled=True, desc=None):
    return (key, value, disabled, desc)


PAGE = [q("page", "0"), q("size", "50")]

login_tests = lambda var: [
    "const j = pm.response.json();",
    "if (j.success) {",
    f"  pm.collectionVariables.set('{var}', j.data.accessToken);",
    f"  pm.collectionVariables.set('{var.replace('Token', 'RefreshToken')}', j.data.refreshToken);",
    "}",
]

SETTINGS = {
    "contributionAmountMinor": 500000, "contributionFrequency": "MONTHLY", "contributionDueDay": 5,
    "lateGraceDays": 5, "latePenaltyBasisPoints": 200, "loanMaxMultiplier": 3, "loanInterestBasisPoints": 1000,
    "loanMinMembershipMonths": 3, "loanGuarantorThresholdMinor": 5000000, "loanGuarantorsRequired": 2,
    "approvalQuorum": 2,
}

items = [
    folder("0. Log in", [
        req("Log in as platform admin", "POST", "/api/v1/auth/login",
            {"email": "{{platformEmail}}", "password": "{{platformPassword}}"}, auth="none",
            tests=login_tests("platformToken"),
            desc="No X-Chama-Code header: platform accounts log in to the platform. Saves {{platformToken}}."),
        req("Log in as chama official", "POST", "/api/v1/auth/login",
            {"email": "{{officialEmail}}", "password": "{{officialPassword}}"}, auth="none", chama_header=True,
            tests=login_tests("officialToken"),
            desc="An official of {{chamaCode}} (e.g. the chairperson). Saves {{officialToken}}, used by most requests."),
        req("Log in as member", "POST", "/api/v1/auth/login",
            {"email": "{{memberEmail}}", "password": "{{memberPassword}}"}, auth="none", chama_header=True,
            tests=login_tests("memberToken"),
            desc="An ordinary member of {{chamaCode}}. Saves {{memberToken}}, used by the 'my ...' requests."),
    ], desc="Run these first. Each saves its token in a collection variable."),

    folder("Auth", [
        req("Register (self-signup into a chama)", "POST", "/api/v1/auth/register",
            {"email": "new.member@example.com", "phoneNumber": "0712345678", "password": "Member2026pass",
             "firstName": "New", "lastName": "Member"}, auth="none", chama_header=True,
            desc="Creates the account and a membership application (PENDING_APPROVAL). A verification email is sent."),
        req("Verify email", "POST", "/api/v1/auth/email/verify", {"token": "<token from the email>"},
            auth="none", chama_header=True),
        req("Resend verification email", "POST", "/api/v1/auth/email/verify/resend",
            {"email": "new.member@example.com"}, auth="none", chama_header=True),
        req("Forgot password", "POST", "/api/v1/auth/password/forgot", {"email": "{{memberEmail}}"},
            auth="none", chama_header=True),
        req("Reset password", "POST", "/api/v1/auth/password/reset",
            {"token": "<token from the email>", "newPassword": "NewPass2026x"}, auth="none", chama_header=True),
        req("Refresh tokens (official)", "POST", "/api/v1/auth/refresh",
            {"refreshToken": "{{officialRefreshToken}}"}, auth="none", tests=login_tests("officialToken")),
        req("Who am I", "GET", "/api/v1/auth/me"),
        req("Change password", "POST", "/api/v1/auth/password/change",
            {"currentPassword": "{{officialPassword}}", "newPassword": "NewPass2026x"}),
        req("Log out (this session)", "POST", "/api/v1/auth/logout", {"refreshToken": "{{officialRefreshToken}}"}),
        req("Log out everywhere", "POST", "/api/v1/auth/logout-all"),
    ]),

    folder("Platform (admin only)", [
        req("Onboard a chama", "POST", "/api/v1/platform/chamas", {
            "chama": {"name": "Baraka Chama", "code": "baraka", "description": "Savings group",
                      "currency": "KES", "settings": SETTINGS,
                      "admin": {"email": "chair@baraka.example", "phoneNumber": "0722000111",
                                "password": "Baraka2026pass", "firstName": "Amina", "lastName": "Otieno"}},
            "license": {"plan": "STANDARD"}}, auth="platform",
            tests=save("chamaId", "j.data.chama.id"),
            desc="The only way to create a chama. The admin becomes its chairperson. Licence defaults to TRIAL."),
        req("List chamas", "GET", "/api/v1/platform/chamas", auth="platform", query=PAGE,
            tests=save("chamaId", "j.data.find(c => c.chama.code === pm.collectionVariables.get('chamaCode')).chama.id")),
        req("Get a chama", "GET", "/api/v1/platform/chamas/{{chamaId}}", auth="platform"),
        req("Update licence", "PUT", "/api/v1/platform/chamas/{{chamaId}}/license", {
            "plan": "STANDARD", "validUntil": "2027-12-31T23:59:59Z", "maxMembers": 100,
            "features": ["CONTRIBUTIONS", "PENALTIES", "LOANS", "EXPENSES", "REPORTS"],
            "status": "ACTIVE", "notes": "Annual renewal"}, auth="platform",
            desc="Every field is optional; null fields are left unchanged."),
    ], desc="Uses {{platformToken}}."),

    folder("My chama", [
        req("Get my chama", "GET", "/api/v1/chama"),
        req("Get licence", "GET", "/api/v1/chama/license"),
        req("Change settings", "PUT", "/api/v1/chama/settings", SETTINGS,
            desc="Amounts are minor units (500000 = KES 5,000). Rates are basis points (200 = 2%)."),
    ]),

    folder("Members & roles", [
        req("List members", "GET", "/api/v1/members",
            query=[q("status", "ACTIVE", desc="PENDING_APPROVAL, ACTIVE, SUSPENDED, ...")] + PAGE,
            tests=[
                "const j = pm.response.json();",
                "const m = (j.data || []).find(x => x.email === pm.collectionVariables.get('memberEmail'));",
                "if (m) { pm.collectionVariables.set('membershipId', m.membershipId); "
                "pm.collectionVariables.set('memberUserId', m.userId); }",
                "const g = (j.data || []).find(x => x.email !== pm.collectionVariables.get('memberEmail')"
                " && x.email !== pm.collectionVariables.get('officialEmail'));",
                "if (g) pm.collectionVariables.set('guarantorUserId', g.userId);",
            ], desc="Saves {{membershipId}} and {{memberUserId}} of {{memberEmail}}, and a {{guarantorUserId}}."),
        req("My membership", "GET", "/api/v1/members/me", auth="member"),
        req("Invite by email", "POST", "/api/v1/members/invitations", {"email": "friend@example.com"}),
        req("Approve application", "POST", "/api/v1/members/{{membershipId}}/approve"),
        req("Reject application", "POST", "/api/v1/members/{{membershipId}}/reject"),
        req("Suspend member", "POST", "/api/v1/members/{{membershipId}}/suspend", {"reason": "Three months in arrears"}),
        req("Reactivate member", "POST", "/api/v1/members/{{membershipId}}/reactivate"),
        req("Change roles", "PUT", "/api/v1/members/{{membershipId}}/roles", {"roles": ["MEMBER", "TREASURER"]},
            desc="Roles: MEMBER, CHAIRPERSON, TREASURER, SECRETARY, AUDITOR."),
        req("Roles and their permissions", "GET", "/api/v1/roles"),
        req("Change a role's permissions", "PUT", "/api/v1/roles/AUDITOR/permissions",
            {"permissions": ["CHAMA_VIEW", "MEMBER_VIEW", "CONTRIBUTION_VIEW_ALL", "LOAN_VIEW_ALL", "PENALTY_VIEW_ALL",
                             "LEDGER_VIEW", "REPORT_VIEW_ALL", "AUDIT_LOG_VIEW"]}),
    ]),

    folder("Contributions", [
        req("Record a contribution", "POST", "/api/v1/contributions", {
            "memberUserId": "{{memberUserId}}", "amountMinor": 500000, "method": "MPESA",
            "reference": "QK{{$timestamp}}", "paidOn": None,
            "notes": "November contribution"},
            tests=save("paymentId", "j.data.id"),
            desc="Methods CASH, BANK, MPESA; MPESA and BANK need a reference. paidOn defaults to today. "
                 "Split across the member's savings products automatically (oldest dues first, by product "
                 "priority; the rest to Deposits), or say how with \"allocations\": "
                 "[{\"productId\": \"{{savingsProductId}}\", \"amountMinor\": 500000}] adding up to the amount."),
        req("List payments", "GET", "/api/v1/contributions",
            query=[q("memberUserId", "{{memberUserId}}"), q("from", "2026-01-01"), q("to", "2026-12-31"),
                   q("status", "RECORDED")] + PAGE),
        req("One payment", "GET", "/api/v1/contributions/{{paymentId}}"),
        req("Reverse a payment", "POST", "/api/v1/contributions/{{paymentId}}/reverse",
            {"reason": "Recorded against the wrong member"}),
        req("Periods", "GET", "/api/v1/contributions/periods", tests=save("periodId", "j.data[0].period.id")),
        req("Who paid for a period", "GET", "/api/v1/contributions/periods/{{periodId}}"),
        req("Standings", "GET", "/api/v1/contributions/standings", query=[q("inArrears", "true")]),
        req("My contributions", "GET", "/api/v1/contributions/me", auth="member"),
    ], desc="Needs the CONTRIBUTIONS licence feature."),

    folder("Savings products", [
        req("List savings products", "GET", "/api/v1/savings-products", auth="member",
            tests=save("savingsProductId", "j.data[0].id")),
        req("One savings product", "GET", "/api/v1/savings-products/{{savingsProductId}}", auth="member"),
        req("Add a savings product", "POST", "/api/v1/savings-products", {
            "code": "SHR", "name": "Share capital", "kind": "SHARE_CAPITAL", "contributionMinor": 50000,
            "allocationPriority": 10, "mandatory": True, "countsTowardLoanLimit": None, "refundableOnExit": None,
            "earnsDividend": None, "ledgerAccountCode": None},
            tests=save("savingsProductId", "j.data.id"),
            desc="Kinds DEPOSITS, SHARE_CAPITAL, WELFARE, CUSTOM; null flags take the kind's defaults. Gets its own "
                 "member-ledger account (2010, 2020, ... or 3100 for share capital). A mandatory product opens an "
                 "account for every member. PRODUCTS_MANAGE."),
        req("Change a savings product", "PUT", "/api/v1/savings-products/{{savingsProductId}}", {
            "name": "Share capital", "contributionMinor": 100000, "allocationPriority": 10, "mandatory": True},
            desc="For periods opened from now on. The default product's contribution is the chama's contribution "
                 "amount (change it in the settings)."),
        req("Deactivate a savings product", "POST", "/api/v1/savings-products/{{savingsProductId}}/deactivate"),
        req("Activate a savings product", "POST", "/api/v1/savings-products/{{savingsProductId}}/activate"),
    ], desc="Needs the CONTRIBUTIONS licence feature."),

    folder("Member accounts", [
        req("List member accounts", "GET", "/api/v1/member-accounts",
            query=[q("memberUserId", "{{memberUserId}}"), q("productId", "{{savingsProductId}}"), q("status", "ACTIVE")],
            tests=save("memberAccountId", "j.data[0].id"), desc="MEMBER_ACCOUNTS_VIEW_ALL. With balances."),
        req("One member account", "GET", "/api/v1/member-accounts/{{memberAccountId}}"),
        req("Account statement", "GET", "/api/v1/member-accounts/{{memberAccountId}}/statement",
            query=[q("from", "2026-01-01"), q("to", "2026-12-31")]),
        req("My accounts", "GET", "/api/v1/member-accounts/me", auth="member",
            tests=save("memberAccountId", "j.data.accounts[0].id")),
        req("My account statement", "GET", "/api/v1/member-accounts/me/{{memberAccountId}}/statement", auth="member",
            query=[q("from", "2026-01-01"), q("to", "2026-12-31")]),
        req("Open an account", "POST", "/api/v1/member-accounts",
            {"memberUserId": "{{memberUserId}}", "productId": "{{savingsProductId}}"},
            tests=save("memberAccountId", "j.data.id"),
            desc="For optional products; mandatory ones open by themselves. MEMBER_ACCOUNTS_MANAGE."),
        req("Close an account", "POST", "/api/v1/member-accounts/{{memberAccountId}}/close",
            {"reason": "Member stopped saving for holidays"},
            desc="Zero balance only; never a mandatory product's account while the member is in the chama."),
        req("Reopen an account", "POST", "/api/v1/member-accounts/{{memberAccountId}}/reopen"),
    ], desc="Needs the CONTRIBUTIONS licence feature."),

    folder("Penalties", [
        req("List penalties", "GET", "/api/v1/penalties",
            query=[q("memberUserId", "{{memberUserId}}"), q("status", "OUTSTANDING")] + PAGE,
            tests=save("penaltyId", "j.data[0].id")),
        req("Fine a member", "POST", "/api/v1/penalties/fines",
            {"memberUserId": "{{memberUserId}}", "amountMinor": 20000, "reason": "Missed the monthly meeting"},
            tests=save("penaltyId", "j.data.id")),
        req("One penalty", "GET", "/api/v1/penalties/{{penaltyId}}"),
        req("Record a penalty payment", "POST", "/api/v1/penalties/{{penaltyId}}/payments",
            {"amountMinor": 5000, "method": "CASH", "reference": None, "paidOn": None}),
        req("Waive a penalty", "POST", "/api/v1/penalties/{{penaltyId}}/waive", {"reason": "First offence"},
            desc="Chairperson by default (PENALTY_WAIVE)."),
        req("Assess late contributions now", "POST", "/api/v1/penalties/assess"),
        req("My penalties", "GET", "/api/v1/penalties/me", auth="member"),
    ], desc="Needs the PENALTIES licence feature."),

    folder("Loan products", [
        req("List loan products", "GET", "/api/v1/loan-products", auth="member",
            tests=save("loanProductId", "j.data[0].id")),
        req("One loan product", "GET", "/api/v1/loan-products/{{loanProductId}}", auth="member"),
        req("Add a loan product", "POST", "/api/v1/loan-products", {
            "code": "EMERG", "name": "Emergency loan", "interestBasisPoints": 500, "maxTermMonths": 3,
            "maxMultiplier": 1, "minMembershipMonths": 0, "guarantorThresholdMinor": 0, "guarantorsRequired": 0},
            tests=save("loanProductId", "j.data.id"), desc="PRODUCTS_MANAGE."),
        req("Change a loan product", "PUT", "/api/v1/loan-products/{{loanProductId}}", {
            "name": "Emergency loan", "interestBasisPoints": 600, "maxTermMonths": 6, "maxMultiplier": 1,
            "minMembershipMonths": 0, "guarantorThresholdMinor": 0, "guarantorsRequired": 0},
            desc="For new applications. The default product's terms are the chama's loan settings: only its name changes here."),
        req("Deactivate a loan product", "POST", "/api/v1/loan-products/{{loanProductId}}/deactivate"),
        req("Activate a loan product", "POST", "/api/v1/loan-products/{{loanProductId}}/activate"),
    ], desc="Needs the LOANS licence feature."),

    folder("Loans", [
        req("My eligibility", "GET", "/api/v1/loans/eligibility", auth="member",
            query=[q("productId", "{{loanProductId}}")],
            desc="Under one loan product (the default without productId): the limit (multiple of loan-backing "
                 "savings, less other loans), interest, longest term, guarantor rules and any reasons the member "
                 "cannot borrow."),
        req("Apply for a loan", "POST", "/api/v1/loans",
            {"productId": None, "principalMinor": 1000000, "termMonths": 6, "purpose": "School fees",
             "guarantorUserIds": []},
            auth="member", tests=save("loanId", "j.data.loan.id"),
            desc="productId null means the default product; one loan in progress per product. Above the product's "
                 "guarantor threshold (default KES 50,000) name guarantors, e.g. [\"{{guarantorUserId}}\", ...]."),
        req("My loans", "GET", "/api/v1/loans/me", auth="member"),
        req("Loans I was asked to guarantee", "GET", "/api/v1/loans/guarantees", auth="member"),
        req("Answer a guarantee request", "POST", "/api/v1/loans/{{loanId}}/guarantee", {"accept": True}, auth="member",
            desc="Send as the guarantor (log in as them first)."),
        req("Name another guarantor", "POST", "/api/v1/loans/{{loanId}}/guarantors",
            {"guarantorUserId": "{{guarantorUserId}}"}, auth="member"),
        req("Cancel my application", "POST", "/api/v1/loans/{{loanId}}/cancel", auth="member"),
        req("List loans", "GET", "/api/v1/loans",
            query=[q("status", "PENDING_APPROVAL"), q("memberUserId", "{{memberUserId}}")] + PAGE,
            tests=save("loanId", "j.data[0].id")),
        req("One loan", "GET", "/api/v1/loans/{{loanId}}"),
        req("Approve", "POST", "/api/v1/loans/{{loanId}}/approve", {"note": "Good saver"},
            desc="One official's approval; approved once the quorum of distinct officials approve."),
        req("Reject", "POST", "/api/v1/loans/{{loanId}}/reject", {"reason": "Clear your arrears first"}),
        req("Disburse", "POST", "/api/v1/loans/{{loanId}}/disburse",
            {"method": "MPESA", "reference": "QKOUT{{$timestamp}}", "disbursedOn": None},
            desc="The account it is paid from must hold enough."),
        req("Record a repayment", "POST", "/api/v1/loans/{{loanId}}/repayments",
            {"amountMinor": 183334, "method": "MPESA", "reference": "QKIN{{$timestamp}}", "paidOn": None}),
    ], desc="Needs the LOANS licence feature. Member requests use {{memberToken}}; decisions use {{officialToken}}."),

    folder("Expenses", [
        req("Record an expense", "POST", "/api/v1/expenses",
            {"amountMinor": 300000, "payee": "Community Hall", "description": "Hall hire for the AGM",
             "accountCode": "5000", "investmentId": None, "incurredOn": None},
            tests=save("expenseId", "j.data.id"),
            desc="Treasurer (EXPENSE_CREATE). Awaits approval by the quorum of officials other than the requester. "
                 "Set investmentId to charge it to an active investment, e.g. the Airbnb's rent."),
        req("List expenses", "GET", "/api/v1/expenses",
            query=[q("status", "PENDING_APPROVAL"), q("investmentId", "{{investmentId}}"), q("from", "2026-01-01"),
                   q("to", "2026-12-31")] + PAGE,
            tests=save("expenseId", "j.data[0].id"), desc="Every member can see every expense."),
        req("Spending summary", "GET", "/api/v1/expenses/summary",
            query=[q("from", "2026-11-01"), q("to", "2026-11-30")], desc="Defaults to this month."),
        req("One expense", "GET", "/api/v1/expenses/{{expenseId}}"),
        req("Approve", "POST", "/api/v1/expenses/{{expenseId}}/approve", {"note": "Agreed at the meeting"},
            desc="Chairperson or secretary by default (EXPENSE_APPROVE); not whoever recorded it."),
        req("Reject", "POST", "/api/v1/expenses/{{expenseId}}/reject", {"reason": "Members bring lunch"}),
        req("Cancel", "POST", "/api/v1/expenses/{{expenseId}}/cancel", desc="Only whoever recorded it."),
        req("Pay", "POST", "/api/v1/expenses/{{expenseId}}/pay",
            {"method": "MPESA", "reference": "QKEXP{{$timestamp}}", "paidOn": None},
            desc="Posts Dr the expense account, Cr cash/bank/M-Pesa. The account must hold enough."),
    ], desc="Needs the EXPENSES licence feature. Requests use {{officialToken}}: log in as the right official."),

    folder("Investments", [
        req("Propose an investment", "POST", "/api/v1/investments",
            {"name": "Sanlam money market fund", "type": "MONEY_MARKET_FUND", "institution": "Sanlam",
             "description": "Surplus cash", "amountMinor": 5000000, "expectedReturnBasisPoints": 1200, "maturityDate": None},
            tests=save("investmentId", "j.data.id"),
            desc="Treasurer (INVESTMENT_MANAGE). Types: MONEY_MARKET_FUND, FIXED_DEPOSIT, TREASURY_BILL, TREASURY_BOND, "
                 "SACCO_SHARES, EQUITIES, LAND, BUSINESS, OTHER."),
        req("List investments", "GET", "/api/v1/investments",
            query=[q("status", "ACTIVE"), q("type", "MONEY_MARKET_FUND")] + PAGE,
            tests=save("investmentId", "j.data[0].id"), desc="Every member can see every investment."),
        req("Portfolio", "GET", "/api/v1/investments/portfolio"),
        req("One investment", "GET", "/api/v1/investments/{{investmentId}}"),
        req("Approve", "POST", "/api/v1/investments/{{investmentId}}/approve", {"note": "Agreed at the AGM"},
            desc="Chairperson or secretary by default (INVESTMENT_APPROVE); not whoever proposed it."),
        req("Reject", "POST", "/api/v1/investments/{{investmentId}}/reject", {"reason": "Too risky"}),
        req("Cancel", "POST", "/api/v1/investments/{{investmentId}}/cancel", desc="Only whoever proposed it."),
        req("Fund", "POST", "/api/v1/investments/{{investmentId}}/fund",
            {"method": "MPESA", "reference": "QKINV{{$timestamp}}", "fundedOn": None},
            desc="Puts the approved amount in. The account must hold enough."),
        req("Deposit (top-up)", "POST", "/api/v1/investments/{{investmentId}}/deposits",
            {"amountMinor": 1000000, "method": "MPESA", "reference": "QKDEP{{$timestamp}}", "depositedOn": None,
             "note": "Airbnb bookings sent back to CIC"},
            desc="More money into an active investment, e.g. topping up the money market fund. No new approval."),
        req("Record income", "POST", "/api/v1/investments/{{investmentId}}/income",
            {"amountMinor": 50000, "method": "REINVESTED", "reference": None, "receivedOn": None,
             "note": "October interest"},
            desc="CASH, BANK, MPESA, or REINVESTED (stays in the investment and adds to its book value)."),
        req("Withdraw part", "POST", "/api/v1/investments/{{investmentId}}/withdrawals",
            {"amountMinor": 1000000, "method": "MPESA", "reference": "QKWD{{$timestamp}}", "withdrawnOn": None,
             "note": None}),
        req("Record a valuation", "POST", "/api/v1/investments/{{investmentId}}/valuations",
            {"valueMinor": 5200000, "valuedOn": None, "note": "October statement"},
            desc="Informational: the books keep investments at cost until closed."),
        req("Close", "POST", "/api/v1/investments/{{investmentId}}/close",
            {"proceedsMinor": 4300000, "method": "MPESA", "reference": "QKCL{{$timestamp}}", "closedOn": None,
             "note": "Redeemed"},
            desc="Proceeds less book value is posted as a gain or loss to 4200 Investment income."),
    ], desc="Needs the INVESTMENTS licence feature."),

    folder("Imports", [
        req("Download the template", "GET", "/api/v1/imports/opening-balances/template",
            desc="Use Send and Download to save the .xlsx. Fill it in, then preview it."),
        req("Preview opening balances", "POST", "/api/v1/imports/opening-balances/preview",
            form=[{"key": "file", "type": "file", "src": "", "description": "The filled-in .xlsx template"},
                  {"key": "asOf", "value": "2026-10-31", "type": "text", "description": "The cut-over date"}],
            desc="Checks every row; saves nothing. data.problems lists each problem with its sheet, row and column."),
        req("Import opening balances", "POST", "/api/v1/imports/opening-balances",
            form=[{"key": "file", "type": "file", "src": "", "description": "The filled-in .xlsx template"},
                  {"key": "asOf", "value": "2026-10-31", "type": "text", "description": "The cut-over date"}],
            desc="Once per chama, all or nothing. New members get a welcome email to set their password."),
        req("Import status", "GET", "/api/v1/imports/opening-balances"),
    ], desc="Bring the chama's records over from Excel. Chairperson by default (CHAMA_SETTINGS_MANAGE)."),

    folder("Ledger", [
        req("Chart of accounts", "GET", "/api/v1/ledger/accounts",
            tests=save("accountId", "j.data.find(a => a.code === '1020').id")),
        req("Add an account", "POST", "/api/v1/ledger/accounts",
            {"code": "1011", "name": "Second bank account", "type": "ASSET"}),
        req("Activate / deactivate an account", "PUT", "/api/v1/ledger/accounts/{{accountId}}/status", {"active": True}),
        req("Account statement", "GET", "/api/v1/ledger/accounts/{{accountId}}/statement",
            query=[q("from", "2026-01-01"), q("to", "2026-12-31"), q("memberUserId", "{{memberUserId}}")]),
        req("Journal", "GET", "/api/v1/ledger/entries",
            query=[q("from", "2026-01-01"), q("to", "2026-12-31"), q("sourceType", "LOAN_DISBURSEMENT")] + PAGE,
            tests=save("entryId", "j.data[0].id")),
        req("One entry", "GET", "/api/v1/ledger/entries/{{entryId}}"),
        req("Post a manual entry", "POST", "/api/v1/ledger/entries", {
            "entryDate": None, "description": "Bank charges for November", "reference": "STMT-11",
            "lines": [{"accountCode": "5100", "debitMinor": 15000, "memo": "Monthly fee"},
                      {"accountCode": "1010", "creditMinor": 15000}]},
            tests=save("entryId", "j.data.id")),
        req("Reverse an entry", "POST", "/api/v1/ledger/entries/{{entryId}}/reverse", {"reason": "Posted twice"}),
        req("Trial balance", "GET", "/api/v1/ledger/trial-balance"),
        req("My balances", "GET", "/api/v1/ledger/me", auth="member"),
    ]),

    folder("Audit log", [
        req("Audit log", "GET", "/api/v1/audit-log", query=[q("action", "LOAN_APPROVED")] + PAGE),
    ]),
]

VARIABLES = [
    ("baseUrl", "http://localhost:8080"),
    ("chamaCode", "mokica"),
    ("platformEmail", "admin@chama.local"), ("platformPassword", "Admin2026pass"),
    ("officialEmail", "chair@mokica.test"), ("officialPassword", "Mokica2026pass"),
    ("memberEmail", "wanjiru@example.com"), ("memberPassword", "Wanjiru2026x"),
    ("platformToken", ""), ("platformRefreshToken", ""),
    ("officialToken", ""), ("officialRefreshToken", ""),
    ("memberToken", ""), ("memberRefreshToken", ""),
    ("chamaId", ""), ("membershipId", ""), ("memberUserId", ""), ("guarantorUserId", ""),
    ("paymentId", ""), ("periodId", ""), ("penaltyId", ""), ("loanId", ""), ("expenseId", ""), ("investmentId", ""), ("accountId", ""), ("entryId", ""),
    ("savingsProductId", ""), ("memberAccountId", ""), ("loanProductId", ""),
]

collection = {
    "info": {
        "_postman_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "chama-management-system/api")),
        "name": "Chama Management API",
        "description": (
            "Every endpoint of the Chama Management API (local dev: {{baseUrl}}).\n\n"
            "1. Run the requests in **0. Log in**: they save the platform, official and member tokens.\n"
            "2. Most requests use the official's token; 'my ...' requests use the member's; Platform uses the admin's.\n"
            "3. List and create requests save the ids that later requests use ({{loanId}}, {{penaltyId}}, ...).\n\n"
            "Every response has the same envelope: { success, message, data, meta?, error?, timestamp }. "
            "Amounts are minor units (500000 = KES 5,000); rates are basis points (200 = 2%). "
            "Public auth requests name the chama with the X-Chama-Code header. "
            "See docs/api/response-format.md for error codes, and /swagger-ui.html for the live schema."),
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{officialToken}}", "type": "string"}]},
    "event": script([
        "// JSON responses only: the .xlsx template download is a file.",
        "if ((pm.response.headers.get('Content-Type') || '').includes('json')) {",
        "  pm.test('Response uses the API envelope', () => {",
        "    const j = pm.response.json();",
        "    pm.expect(j).to.have.property('success');",
        "    pm.expect(j).to.have.property('message');",
        "  });",
        "  pm.test(`success matches the HTTP status (${pm.response.code})`, () => {",
        "    pm.expect(pm.response.json().success).to.eql(pm.response.code < 400);",
        "  });",
        "}",
    ]),
    "variable": [{"key": k, "value": v, "type": "string"} for k, v in VARIABLES],
    "item": items,
}

with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    json.dump(collection, f, indent=2, ensure_ascii=False)
    f.write("\n")

count = sum(len(f["item"]) for f in items)
print(f"{count} requests in {len(items)} folders -> {OUT}")
