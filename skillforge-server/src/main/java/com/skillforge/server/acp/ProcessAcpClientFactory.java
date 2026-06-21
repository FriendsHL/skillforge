package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Production {@link AcpClientFactory} — each call spawns a real ACP adapter child
 * process via {@link ProcessAcpTransport} (ACP-EXTERNAL-AGENT P1a-2).
 *
 * <p>The Spring-managed {@link ObjectMapper} (footgun #1: has JavaTimeModule) and
 * the cc-dialect {@link AcpUpdateTranslator} are shared across runs; the adapter
 * package is chosen PER RUN by the caller (cc vs codex vs …), and the
 * transport/process is per-run.
 */
public class ProcessAcpClientFactory implements AcpClientFactory {

    private final ObjectMapper objectMapper;
    private final AcpUpdateTranslator translator;

    public ProcessAcpClientFactory(ObjectMapper objectMapper,
                                   AcpUpdateTranslator translator) {
        this.objectMapper = objectMapper;
        this.translator = translator;
    }

    @Override
    public AcpClient create(String adapterPackage, String cwd, Map<String, String> extraEnv) {
        AcpTransport transport = ProcessAcpTransport.forAdapterPackage(adapterPackage, cwd, extraEnv);
        return new AcpClient(transport, objectMapper, translator);
    }
}
