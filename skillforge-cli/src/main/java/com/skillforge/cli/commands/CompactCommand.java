package com.skillforge.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.cli.ApiClient;
import com.skillforge.cli.SkillforgeCli;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

@Command(name = "compact",
        description = "Manually trigger context compaction for a session.")
public class CompactCommand implements Callable<Integer> {

    @ParentCommand
    SkillforgeCli parent;

    @Parameters(index = "0", description = "Session id") String sessionId;

    @Option(names = "--level", description = "Compaction level (currently only 'full')", defaultValue = "full")
    String level;

    @Option(names = "--reason", description = "Optional reason string")
    String reason;

    @Override
    public Integer call() throws Exception {
        ApiClient api = parent.apiClient();
        JsonNode event = api.compact(sessionId, level, reason);
        System.out.println("compaction id: " + event.path("id").asText());
        System.out.println("level:         " + event.path("level").asText());
        System.out.println("trigger:       " + event.path("triggerSource").asText());
        System.out.println("tokensBefore:  " + event.path("tokensBefore").asText("?"));
        System.out.println("tokensAfter:   " + event.path("tokensAfter").asText("?"));
        return 0;
    }
}
