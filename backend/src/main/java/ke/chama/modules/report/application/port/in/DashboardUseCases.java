package ke.chama.modules.report.application.port.in;

import ke.chama.modules.report.application.port.in.DashboardViews.ChamaDashboardView;
import ke.chama.modules.report.application.port.in.DashboardViews.ChamaOverviewView;
import ke.chama.modules.report.application.port.in.DashboardViews.MemberDashboardView;
import ke.chama.modules.report.application.port.in.DashboardViews.TopInvestmentsView;
import ke.chama.modules.report.application.port.in.DashboardViews.TopMembersView;
import ke.chama.modules.report.application.port.in.DashboardViews.TrendsView;

import java.time.LocalDate;
import java.util.UUID;

/** At-a-glance figures: the whole chama for officials, and each member's own position. */
public interface DashboardUseCases {

    ChamaDashboardView chama(UUID chamaId);

    /**
     * The chama-wide picture any member may see: totals only, nothing about another member.
     * Investment figures only when the viewer holds INVESTMENT_VIEW.
     */
    ChamaOverviewView overview(UUID chamaId, UUID viewerUserId);

    MemberDashboardView member(UUID chamaId, UUID memberUserId);

    /**
     * Members ranked by what they contributed between the two days (inclusive; defaults: 1 January
     * to today). Needs CONTRIBUTIONS.
     */
    TopMembersView topMembers(UUID chamaId, LocalDate from, LocalDate to, int limit);

    /** Funded investments (active and closed) ranked by return. Needs INVESTMENTS. */
    TopInvestmentsView topInvestments(UUID chamaId, int limit);

    /** The last {@code months} calendar months (this one included), oldest first. */
    TrendsView trends(UUID chamaId, int months);
}
