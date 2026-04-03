package com.skillforge.server.service;

import com.skillforge.server.dto.DashboardOverview;
import com.skillforge.server.entity.ModelUsageEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DashboardService {

    private final AgentRepository agentRepository;
    private final SessionRepository sessionRepository;
    private final ModelUsageRepository modelUsageRepository;

    public DashboardService(AgentRepository agentRepository,
                            SessionRepository sessionRepository,
                            ModelUsageRepository modelUsageRepository) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.modelUsageRepository = modelUsageRepository;
    }

    public DashboardOverview getOverview() {
        DashboardOverview overview = new DashboardOverview();

        overview.setTotalAgents(agentRepository.count());
        overview.setActiveAgents(agentRepository.findByStatus("active").size());
        overview.setTotalSessions(sessionRepository.count());

        // 今日会话数：遍历所有 session 统计今天创建的
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long todaySessions = sessionRepository.findAll().stream()
                .filter(s -> s.getCreatedAt() != null && s.getCreatedAt().isAfter(todayStart))
                .count();
        overview.setTodaySessions(todaySessions);

        // 汇总 token 使用量
        List<ModelUsageEntity> allUsage = modelUsageRepository.findAll();
        long totalInput = 0;
        long totalOutput = 0;
        for (ModelUsageEntity u : allUsage) {
            totalInput += u.getInputTokens();
            totalOutput += u.getOutputTokens();
        }
        overview.setTotalInputTokens(totalInput);
        overview.setTotalOutputTokens(totalOutput);

        return overview;
    }
}
