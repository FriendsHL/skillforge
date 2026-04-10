package com.skillforge.cli;

import com.skillforge.cli.commands.AgentsCommand;
import com.skillforge.cli.commands.ChatCommand;
import com.skillforge.cli.commands.CompactCommand;
import com.skillforge.cli.commands.SessionsCommand;
import com.skillforge.cli.commands.SkillsCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * Top-level picocli entrypoint. Dispatches to agents / sessions / chat /
 * skills / compact subcommands. Configuration is resolved lazily via
 * {@link #apiClient()} so unit tests can inject a pre-built client.
 */
@Command(
        name = "skillforge",
        mixinStandardHelpOptions = true,
        version = "skillforge 1.0.0-SNAPSHOT",
        description = "One-shot command-line client for the SkillForge server.",
        subcommands = {
                AgentsCommand.class,
                SessionsCommand.class,
                ChatCommand.class,
                SkillsCommand.class,
                CompactCommand.class
        })
public class SkillforgeCli implements Callable<Integer> {

    @Option(names = "--server", description = "Server base URL (default: ${DEFAULT-VALUE} or $SKILLFORGE_SERVER)")
    String server;

    @Option(names = "--user-id", description = "User id (default: 1 or $SKILLFORGE_USER_ID)")
    Long userId;

    @Option(names = {"-v", "--verbose"}, description = "Log HTTP requests/responses to stderr")
    boolean verbose;

    @Spec
    CommandLine.Model.CommandSpec spec;

    private ApiClient injectedClient;

    /** Test hook: inject a preconfigured ApiClient. */
    public void setApiClient(ApiClient client) {
        this.injectedClient = client;
    }

    public ApiClient apiClient() {
        if (injectedClient != null) return injectedClient;
        CliConfig cfg = CliConfig.resolve(server, userId);
        return new ApiClient(cfg.getServer(), cfg.getUserId(), verbose);
    }

    public boolean isVerbose() { return verbose; }

    @Override
    public Integer call() {
        throw new ParameterException(spec.commandLine(),
                "Missing required subcommand. Try --help.");
    }

    public static void main(String[] args) {
        SkillforgeCli cli = new SkillforgeCli();
        CommandLine cmd = new CommandLine(cli);
        cmd.setCaseInsensitiveEnumValuesAllowed(true);
        int exit = cmd.execute(args);
        System.exit(exit);
    }
}
