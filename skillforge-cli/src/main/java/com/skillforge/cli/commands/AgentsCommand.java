package com.skillforge.cli.commands;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.cli.ApiClient;
import com.skillforge.cli.OutputFormat;
import com.skillforge.cli.SkillforgeCli;
import com.skillforge.cli.YamlMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "agents",
        description = "Manage agents (list / show / create / export / delete).",
        subcommands = {
                AgentsCommand.ListCmd.class,
                AgentsCommand.ShowCmd.class,
                AgentsCommand.CreateCmd.class,
                AgentsCommand.ExportCmd.class,
                AgentsCommand.DeleteCmd.class
        })
public class AgentsCommand implements Callable<Integer> {

    @ParentCommand
    SkillforgeCli parent;

    @Override
    public Integer call() {
        System.err.println("Usage: skillforge agents <list|show|create|export|delete>");
        return 2;
    }

    // ---------- list ----------
    @Command(name = "list", description = "List all agents as a table.")
    public static class ListCmd implements Callable<Integer> {
        @ParentCommand AgentsCommand parent;
        @Option(names = "--json", description = "Output as JSON") boolean asJson;

        @Override
        public Integer call() throws Exception {
            ApiClient api = parent.parent.apiClient();
            JsonNode node = api.listAgents();
            if (asJson) {
                System.out.println(YamlMapper.json().writerWithDefaultPrettyPrinter().writeValueAsString(node));
                return 0;
            }
            List<Map<String, Object>> rows = api.toMapList(node);
            List<String> headers = List.of("ID", "NAME", "MODEL", "SKILLS", "MODE", "PUBLIC");
            List<List<String>> tableRows = new ArrayList<>();
            for (Map<String, Object> a : rows) {
                int skillCount = 0;
                Object s = a.get("skillIds");
                if (s instanceof String && !((String) s).isBlank()) {
                    try {
                        List<String> ids = YamlMapper.json().readValue((String) s, new TypeReference<List<String>>() {});
                        skillCount = ids.size();
                    } catch (Exception ignored) {}
                }
                tableRows.add(List.of(
                        String.valueOf(a.get("id")),
                        str(a.get("name")),
                        str(a.get("modelId")),
                        String.valueOf(skillCount),
                        str(a.get("executionMode")),
                        String.valueOf(a.getOrDefault("public", false))
                ));
            }
            System.out.print(OutputFormat.renderTable(headers, tableRows));
            return 0;
        }
    }

    // ---------- show ----------
    @Command(name = "show", description = "Show an agent's full details.")
    public static class ShowCmd implements Callable<Integer> {
        @ParentCommand AgentsCommand parent;
        @Parameters(index = "0", description = "Agent id") long id;
        @Option(names = "--yaml", description = "Print as YAML") boolean asYaml;
        @Option(names = "--json", description = "Print as JSON") boolean asJson;

        @Override
        public Integer call() throws Exception {
            ApiClient api = parent.parent.apiClient();
            JsonNode node = api.getAgent(id);
            if (asYaml) {
                String yaml = api.exportAgentYaml(id);
                System.out.print(yaml);
                if (!yaml.endsWith("\n")) System.out.println();
                return 0;
            }
            if (asJson) {
                System.out.println(YamlMapper.json().writerWithDefaultPrettyPrinter().writeValueAsString(node));
                return 0;
            }
            Map<String, Object> a = api.toMap(node);
            System.out.println("id:           " + a.get("id"));
            System.out.println("name:         " + a.get("name"));
            System.out.println("modelId:      " + a.get("modelId"));
            System.out.println("executionMode:" + a.get("executionMode"));
            System.out.println("public:       " + a.get("public"));
            System.out.println("status:       " + a.get("status"));
            System.out.println("description:  " + nullSafe(a.get("description")));
            System.out.println("skillIds:     " + nullSafe(a.get("skillIds")));
            System.out.println("systemPrompt: " + truncate(nullSafe(a.get("systemPrompt")), 500));
            return 0;
        }
    }

    // ---------- create ----------
    @Command(name = "create", description = "Import an agent from a YAML file (POST /api/agents/import).")
    public static class CreateCmd implements Callable<Integer> {
        @ParentCommand AgentsCommand parent;
        @Option(names = {"-f", "--file"}, description = "Path to YAML file (or - for stdin)", required = true)
        String file;

        @Override
        public Integer call() throws Exception {
            String yaml;
            if ("-".equals(file)) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                yaml = sb.toString();
            } else {
                yaml = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            }
            ApiClient api = parent.parent.apiClient();
            JsonNode created = api.importAgentYaml(yaml);
            Map<String, Object> a = api.toMap(created);
            System.out.println("Created agent id=" + a.get("id") + " name=" + a.get("name"));
            return 0;
        }
    }

    // ---------- export ----------
    @Command(name = "export", description = "Export an agent as YAML to stdout.")
    public static class ExportCmd implements Callable<Integer> {
        @ParentCommand AgentsCommand parent;
        @Parameters(index = "0", description = "Agent id") long id;

        @Override
        public Integer call() throws Exception {
            ApiClient api = parent.parent.apiClient();
            String yaml = api.exportAgentYaml(id);
            System.out.print(yaml);
            if (!yaml.endsWith("\n")) System.out.println();
            return 0;
        }
    }

    // ---------- delete ----------
    @Command(name = "delete", description = "Delete an agent (prompts unless --yes).")
    public static class DeleteCmd implements Callable<Integer> {
        @ParentCommand AgentsCommand parent;
        @Parameters(index = "0", description = "Agent id") long id;
        @Option(names = "--yes", description = "Skip confirmation") boolean yes;

        @Override
        public Integer call() throws Exception {
            if (!yes) {
                System.err.print("Delete agent " + id + "? [y/N]: ");
                BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line = r.readLine();
                if (line == null || !(line.trim().equalsIgnoreCase("y") || line.trim().equalsIgnoreCase("yes"))) {
                    System.err.println("Aborted.");
                    return 1;
                }
            }
            parent.parent.apiClient().deleteAgent(id);
            System.out.println("Deleted agent " + id);
            return 0;
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static String nullSafe(Object o) { return o == null ? "" : o.toString(); }
    private static String truncate(String s, int n) {
        if (s == null) return "";
        if (s.length() <= n) return s;
        return s.substring(0, n) + "...";
    }
}
