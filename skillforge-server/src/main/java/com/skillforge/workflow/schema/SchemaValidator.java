package com.skillforge.workflow.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AUTOEVOLVING V1 Sprint 2 (Task E): validates an {@code agent('...', {schema})}
 * call's parsed output against the caller-supplied JSON Schema, using
 * {@code com.networknt:json-schema-validator}.
 *
 * <p>Defaults to JSON Schema draft 2020-12; a schema carrying its own
 * {@code $schema} keyword is honoured by the factory. The validator is
 * stateless / thread-safe so a single Spring singleton is shared across runs and
 * across the offloaded {@code agent()} worker threads.
 */
@Service
public class SchemaValidator {

    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /**
     * Validates {@code instance} against {@code schemaNode}.
     *
     * @return an empty list when valid; otherwise the validation messages as
     *         strings (suitable for a retry prompt / exception detail)
     */
    public List<String> validate(JsonNode instance, JsonNode schemaNode) {
        JsonSchema schema = factory.getSchema(schemaNode);
        Set<ValidationMessage> messages = schema.validate(instance);
        if (messages.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(messages.size());
        for (ValidationMessage m : messages) {
            out.add(m.getMessage());
        }
        return out;
    }
}
