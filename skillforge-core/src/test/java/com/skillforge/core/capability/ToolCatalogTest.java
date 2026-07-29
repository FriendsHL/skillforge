package com.skillforge.core.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void search_returnsOnlyAuthorizedDescriptorsInDeterministicOrder() {
        ToolCatalog catalog = ToolCatalog.fromAuthorizedSchemas(List.of(
                schema("mcp_web_search", "Search the public web"),
                schema("mcp_web_fetch", "Fetch a web page"),
                schema("generate_image", "Generate an image")), mapper);

        List<ToolDescriptor> matches = catalog.search("web search", 10);

        assertThat(matches).extracting(ToolDescriptor::name)
                .containsExactly("mcp_web_search", "mcp_web_fetch");
        assertThat(matches).allSatisfy(descriptor -> {
            assertThat(descriptor.schemaHash()).hasSize(64);
            assertThat(descriptor.kind()).isEqualTo(ToolKind.MCP);
        });
        assertThat(catalog.search("database migration", 10)).isEmpty();
    }

    @Test
    void deferredSurface_loadsOnlyAlwaysLoadedUntilCurrentSchemaHashIsDiscovered() {
        ToolSchema bash = schema("Bash", "Run a command");
        ToolSchema media = schema("generate_image", "Generate an image");
        ToolCatalog catalog =
                ToolCatalog.fromAuthorizedSchemas(List.of(bash, media), mapper);
        ToolDiscoveryState state = new ToolDiscoveryState();

        assertThat(catalog.exposedSchemas(state, true))
                .extracting(ToolSchema::getName)
                .containsExactly("Bash");

        ToolDescriptor image = catalog.findByName("generate_image");
        state.discover(image);

        assertThat(catalog.exposedSchemas(state, true))
                .extracting(ToolSchema::getName)
                .containsExactly("Bash", "generate_image");

        ToolSchema changed = schema("generate_image", "Generate an image with a changed schema");
        ToolCatalog changedCatalog =
                ToolCatalog.fromAuthorizedSchemas(List.of(bash, changed), mapper);
        assertThat(changedCatalog.exposedSchemas(state, true))
                .extracting(ToolSchema::getName)
                .containsExactly("Bash");
    }

    @Test
    void arkStyleMediaAndMcpSchemasAreDeferredWithoutHidingCoreTools() {
        ToolSchema bash = schema("Bash", "Run shell commands");
        ToolSchema image = schema(
                "GenerateImage", "Generate an image with the configured Ark provider");
        ToolSchema mcp = schema(
                "mcp_research_search", "Search research sources through MCP");
        ToolCatalog catalog =
                ToolCatalog.fromAuthorizedSchemas(List.of(bash, image, mcp), mapper);

        List<ToolSchema> initial =
                catalog.exposedSchemas(new ToolDiscoveryState(), true);

        assertThat(initial).extracting(ToolSchema::getName).containsExactly("Bash");
        assertThat(catalog.findByName("GenerateImage").kind()).isEqualTo(ToolKind.MEDIA);
        assertThat(catalog.findByName("mcp_research_search").kind()).isEqualTo(ToolKind.MCP);
        int fullTokens = catalog.descriptors().stream()
                .mapToInt(ToolDescriptor::schemaTokenCost)
                .sum();
        assertThat(catalog.findByName("Bash").schemaTokenCost()).isLessThan(fullTokens);
    }

    private static ToolSchema schema(String name, String description) {
        return new ToolSchema(name, description, Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string"))));
    }
}
