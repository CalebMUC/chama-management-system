package ke.chama.modules.report.application.port.in;

import ke.chama.modules.report.application.port.in.DashboardViews.ChamaDashboardView;
import ke.chama.modules.report.application.port.in.DashboardViews.MemberDashboardView;

import java.util.UUID;

/** At-a-glance figures: the whole chama for officials, and each member's own position. */
public interface DashboardUseCases {

    ChamaDashboardView chama(UUID chamaId);

    MemberDashboardView member(UUID chamaId, UUID memberUserId);
}
