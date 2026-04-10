package com.skillforge.cli;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentsCommandTest {

    MockWebServer server;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String url = server.url("").toString();
        base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private int execute(ByteArrayOutputStream stdout, String... args) {
        SkillforgeCli cli = new SkillforgeCli();
        cli.setApiClient(new ApiClient(base, 1L, false));
        CommandLine cmd = new CommandLine(cli);
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(stdout));
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            return cmd.execute(args);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    @Test
    void agentsListRendersTable() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":1,\"name\":\"Foo\",\"modelId\":\"m\",\"skillIds\":\"[\\\"Bash\\\"]\",\"executionMode\":\"ask\",\"public\":false}]"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = execute(out, "agents", "list");
        assertThat(code).isEqualTo(0);
        String s = out.toString();
        assertThat(s).contains("ID").contains("Foo").contains("ask");
    }

    @Test
    void agentsShowPrintsFields() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":1,\"name\":\"Foo\",\"modelId\":\"m\",\"executionMode\":\"ask\",\"public\":false,\"status\":\"active\",\"systemPrompt\":\"hi\",\"skillIds\":\"[]\"}"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = execute(out, "agents", "show", "1");
        assertThat(code).isEqualTo(0);
        assertThat(out.toString()).contains("name:").contains("Foo");
    }

    @Test
    void agentsCreateReadsYamlFileAndPosts() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":99,\"name\":\"X\"}"));
        Path tmp = Files.createTempFile("agent-", ".yaml");
        Files.writeString(tmp, "name: X\nskills:\n  - Bash\n");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code = execute(out, "agents", "create", "-f", tmp.toString());
            assertThat(code).isEqualTo(0);
            assertThat(out.toString()).contains("id=99");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void agentsExportPrintsYamlFromServer() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/yaml")
                .setBody("name: X\nskills:\n  - Bash\n"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = execute(out, "agents", "export", "1");
        assertThat(code).isEqualTo(0);
        assertThat(out.toString()).contains("name: X");
    }
}
