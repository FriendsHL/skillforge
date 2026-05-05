package com.skillforge.server.service;

import com.skillforge.server.dto.AgentUsageDto;
import com.skillforge.server.dto.DailyUsageDto;
import com.skillforge.server.dto.DashboardOverview;
import com.skillforge.server.dto.ModelUsageDto;
import com.skillforge.server.entity.ModelUsageEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

        // 今日会话数:遍历所有 session 统计今天创建的
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long todaySessions = sessionRepository.findAll().stream()
                .filter(s -> s.getCreatedAt() != null && s.getCreatedAt().isAfter(todayStart))
                .count();
        overview.setTodaySessions(todaySessions);

        // 汇总历史 token(全量)
        List<ModelUsageEntity> allUsage = modelUsageRepository.findAll();
        long totalInput = 0;
        long totalOutput = 0;
        for (ModelUsageEntity u : allUsage) {
            totalInput += u.getInputTokens();
            totalOutput += u.getOutputTokens();
        }
        overview.setTotalInputTokens(totalInput);
        overview.setTotalOutputTokens(totalOutput);

        // 今日 token(SQL 聚合,避免再扫一遍全表)
        List<Object[]> todayRows = modelUsageRepository.sumTokensSince(todayStart);
        long todayInput = 0L;
        long todayOutput = 0L;
        if (todayRows != null && !todayRows.isEmpty()) {
            Object[] row = todayRows.get(0);
            if (row != null && row.length >= 2) {
                if (row[0] instanceof Number) todayInput = ((Number) row[0]).longValue();
                if (row[1] instanceof Number) todayOutput = ((Number) row[1]).longValue();
            }
        }
        overview.setTodayInputTokens(todayInput);
        overview.setTodayOutputTokens(todayOutput);

        return overview;
    }

    public List<DailyUsageDto> getDailyUsage(int days) {
        // EVAL-V2 M3a §2.2 R3: dashboard 默认只看 production 流量；eval 流量不算 cost。
        LocalDateTime since = LocalDate.now().minusDays(days).atStartOfDay();
        List<Object[]> rows = modelUsageRepository.findDailyUsage(since, SessionEntity.ORIGIN_PRODUCTION);
        List<DailyUsageDto> result = new ArrayList<>();
        for (Object[] row : rows) {
            String date = row[0].toString();
            long inputTokens = ((Number) row[1]).longValue();
            long outputTokens = ((Number) row[2]).longValue();
            result.add(new DailyUsageDto(date, inputTokens, outputTokens));
        }
        return result;
    }

    public List<ModelUsageDto> getUsageByModel() {
        // EVAL-V2 M3a §2.2 R3: production 默认。
        List<Object[]> rows = modelUsageRepository.findUsageByModel(SessionEntity.ORIGIN_PRODUCTION);
        List<ModelUsageDto> result = new ArrayList<>();
        for (Object[] row : rows) {
            String model = (String) row[0];
            long totalTokens = ((Number) row[1]).longValue();
            result.add(new ModelUsageDto(model != null ? model : "unknown", totalTokens));
        }
        return result;
    }

    public List<AgentUsageDto> getUsageByAgent() {
        // EVAL-V2 M3a §2.2 R3: production 默认。
        List<Object[]> rows = modelUsageRepository.findUsageByAgent(SessionEntity.ORIGIN_PRODUCTION);
        List<AgentUsageDto> result = new ArrayList<>();
        for (Object[] row : rows) {
            String agentName = (String) row[0];
            long totalTokens = ((Number) row[1]).longValue();
            result.add(new AgentUsageDto(agentName != null ? agentName : "Unknown", totalTokens));
        }
        return result;
    }
}
