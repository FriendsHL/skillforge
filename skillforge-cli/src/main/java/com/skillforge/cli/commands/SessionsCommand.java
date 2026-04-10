package com.skillforge.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.cli.ApiClient;
import com.skillforge.cli.OutputFormat;
import com.skillforge.cli.SkillforgeCli;
import com.skillforge.cli.YamlMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "sessions",
        description = "List / show / messages / cancel sessions.",
        subcommands = {
                SessionsCommand.ListCmd.class,
                SessionsCommand.ShowCmd.class,
                SessionsCommand.MessagesCmd.class,
                SessionsCommand.CancelCmd.class
        })
public class SessionsCommand implements Callable<Integer> {

    @ParentCommand
    SkillforgeCli parent;

    @Override
    public Integer call() {
        System.err.println("Usage: skillforge sessions <list|show|messages|cancel>");
        return 2;
    }

    @Command(name = "list", description = "List sessions for a user.")
    public static class ListCmd implements Callable<Integer> {
        @ParentCommand SessionsCommand parent;
        @Option(names = "--user", description = "User id override") Long user;
        @Option(names = "--json", description = "Output as JSON") boolean asJson;

        @Override
        public Integer call() throws Exception {
            ApiClient api = parent.parent.apiClient();
            long uid = user != null ? user : api.userId();
            JsonNode node = api.listSessions(uid);
            if (asJson) {
                System.out.println(YamlMapper.json().writerWithDefaultPrettyPrinter().writeValueAsString(node));
                return 0;
            }
            List<Map<String, Object>> rows = api.toMapList(node);
            List<String> headers = List.of("ID", "TITLE", "AGENT", "MSGS", "STATUS", "UPDATED");
            List<List<String>> tableRows = new ArrayList<>();
            for (Map<String, Object> s : rows) {
                String id = String.valueOf(s.get("id"));
                String shortId = id.length() > 8 ? id.substring(0, 8) : id;
                tableRows.add(List.of(
                        shortId,
                        str(s.get("title")),
                        str(s.get("agentId")),
                        String.valueOf(s.getOrDefault("messageCount", 0)),
                        str(s.get("runtimeStatus")),
                        str(s.get("updatedAt"))
                ));
            }
            System.out.print(OutputFormat.renderTable(headers, tableRows));
            return 0;
        }
    }

    @Command(name = "show", description = "Show a session's detail.")
    public static class ShowCmd implements Callable<Integer> {
        @ParentCommand SessionsCommand parent;
        @Parameters(index = "0", description = "Session id") String id;

        @Override
        public Integer call() throws Exception {
            ApiClient api = parent.parent.apiClient();
            JsonNode node = api.getSession(id);
            System.out.println(YamlMapper.json().writerWithDefaultPrettyPrinter().writeValueAsString(node));
            return 0;
        }
    }

    @Command(name = "messages", description = "Print formatted message history.")
    public static class MessagesCmd implements Callable<Integer> {
        @ParentCommand SessionsCommand parent;
        @Parameters(index = "0", description = "Session id") String id;

        @Override
        public Integer call() throws Exception {
            ApiClient api = parent.parent.apiClient();
            JsonNode node = api.getSessionMessages(id);
            List<Map<String, Object>> msgs = api.toMapList(node);
            for (Map<String, Object> m : msgs) {
                String role = String.valueOf(m.get("role"));
                Object content = m.get("content");
                System.out.println("[" + role + "]");
                System.out.println(content == null ? "" : content);
                System.out.println();
            }
            return 0;
        }
    }

    @Command(name = "cancel", description = "Cancel a running session loop.")
    public static class CancelCmd implements Callable<Integer> {
        @ParentCommand SessionsCommand parent;
        @Parameters(index = "0", description = "Session id") String id;

        @Override
        public Integer call() throws Exception {
            ApiClient api = parent.parent.apiClient();
            JsonNode r = api.cancelSession(id);
            System.out.println(r);
            return 0;
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
