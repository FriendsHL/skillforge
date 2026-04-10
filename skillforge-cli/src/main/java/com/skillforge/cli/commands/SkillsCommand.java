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

@Command(name = "skills",
        description = "List available skills (built-in + installed).",
        subcommands = {
                SkillsCommand.ListCmd.class,
                SkillsCommand.InstallCmd.class
        })
public class SkillsCommand implements Callable<Integer> {

    @ParentCommand
    SkillforgeCli parent;

    @Override
    public Integer call() {
        System.err.println("Usage: skillforge skills <list|install>");
        return 2;
    }

    @Command(name = "list", description = "List built-in and installed skills.")
    public static class ListCmd implements Callable<Integer> {
        @ParentCommand SkillsCommand parent;
        @Option(names = "--json", description = "Output as JSON") boolean asJson;

        @Override
        public Integer call() throws Exception {
            ApiClient api = parent.parent.apiClient();
            JsonNode builtin = api.listBuiltinSkills();
            JsonNode installed = api.listSkills();
            if (asJson) {
                Map<String, Object> combined = Map.of("builtin", builtin, "installed", installed);
                System.out.println(YamlMapper.json().writerWithDefaultPrettyPrinter().writeValueAsString(combined));
                return 0;
            }
            List<String> headers = List.of("NAME", "TYPE", "READONLY", "DESCRIPTION");
            List<List<String>> rows = new ArrayList<>();
            for (Map<String, Object> b : api.toMapList(builtin)) {
                rows.add(List.of(
                        str(b.get("name")),
                        "builtin",
                        String.valueOf(b.getOrDefault("readOnly", false)),
                        truncate(str(b.get("description")), 60)
                ));
            }
            for (Map<String, Object> s : api.toMapList(installed)) {
                rows.add(List.of(
                        str(s.get("name")),
                        "package",
                        "",
                        truncate(str(s.get("description")), 60)
                ));
            }
            System.out.print(OutputFormat.renderTable(headers, rows));
            return 0;
        }
    }

    @Command(name = "install", description = "Install a skill from ClawHub by slug.")
    public static class InstallCmd implements Callable<Integer> {
        @ParentCommand SkillsCommand parent;
        @Parameters(index = "0", description = "ClawHub slug") String slug;

        @Override
        public Integer call() {
            System.err.println("skills install: not yet exposed as a REST endpoint.");
            System.err.println("For now, install via the dashboard or by asking an agent that has the ClawHub skill to:");
            System.err.println("  \"install skill " + slug + " from clawhub\"");
            return 1;
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static String truncate(String s, int n) {
        if (s == null) return "";
        if (s.length() <= n) return s;
        return s.substring(0, n) + "...";
    }
}
