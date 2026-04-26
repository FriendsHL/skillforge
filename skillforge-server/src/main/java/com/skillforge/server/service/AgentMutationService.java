package com.skillforge.server.service;

import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.entity.AgentEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentMutationService {

    private final AgentService agentService;
    private final AgentAuthoredHookService agentAuthoredHookService;

    public AgentMutationService(AgentService agentService,
                                AgentAuthoredHookService agentAuthoredHookService) {
        this.agentService = agentService;
        this.agentAuthoredHookService = agentAuthoredHookService;
    }

    @Transactional
    public UpdateResult updateAgent(Long targetAgentId,
                                    AgentEntity patch,
                                    List<AgentAuthoredHookService.ProposeRequest> hookProposals) {
        AgentEntity updated = patch != null
                ? agentService.updateAgent(targetAgentId, patch)
                : agentService.getAgent(targetAgentId);

        List<AgentAuthoredHookEntity> proposed = new ArrayList<>();
        if (hookProposals != null) {
            for (AgentAuthoredHookService.ProposeRequest proposal : hookProposals) {
                proposed.add(agentAuthoredHookService.propose(proposal));
            }
        }
        return new UpdateResult(updated, proposed);
    }

    public record UpdateResult(AgentEntity agent, List<AgentAuthoredHookEntity> proposedHooks) {}
}
