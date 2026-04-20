package com.skillforge.server.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.platform.feishu.FeishuClient;
import com.skillforge.server.channel.platform.telegram.TelegramBotClient;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.entity.ChannelConfigEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelConfigControllerTest {

    @Test
    @DisplayName("patch should return restart warning when mode changes")
    void patch_modeChanged_returnsWarning() {
        ChannelConfigService configService = mock(ChannelConfigService.class);
        ChannelAdapterRegistry registry = mock(ChannelAdapterRegistry.class);
        FeishuClient feishuClient = mock(FeishuClient.class);
        TelegramBotClient telegramBotClient = mock(TelegramBotClient.class);
        ChannelConfigController controller = new ChannelConfigController(
                configService, registry, new ObjectMapper(), feishuClient, telegramBotClient);

        ChannelConfigEntity existing = new ChannelConfigEntity();
        existing.setId(1L);
        existing.setPlatform("feishu");
        existing.setDisplayName("Feishu");
        existing.setConfigJson("{\"mode\":\"webhook\"}");
        existing.setDefaultAgentId(100L);
        existing.setWebhookSecret("secret");
        existing.setCredentialsJson("{\"app_id\":\"cli_abc\"}");
        existing.setActive(true);

        ChannelConfigEntity saved = new ChannelConfigEntity();
        saved.setId(1L);
        saved.setPlatform("feishu");
        saved.setDisplayName("Feishu");
        saved.setConfigJson("{\"mode\":\"websocket\"}");
        saved.setDefaultAgentId(100L);
        saved.setWebhookSecret("secret");
        saved.setCredentialsJson("{\"app_id\":\"cli_abc\"}");
        saved.setActive(true);

        when(configService.getById(1L)).thenReturn(Optional.of(existing));
        when(configService.save(any(ChannelConfigEntity.class))).thenReturn(saved);

        ChannelConfigController.ConfigRequest request = new ChannelConfigController.ConfigRequest();
        request.configJson = "{\"mode\":\"websocket\"}";

        ResponseEntity<?> response = controller.patch(1L, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(ChannelConfigController.ConfigView.class);
        ChannelConfigController.ConfigView body = (ChannelConfigController.ConfigView) response.getBody();
        assertThat(body.warning()).isEqualTo("ws mode change requires server restart");
    }

    @Test
    @DisplayName("patch should not return warning when mode does not change")
    void patch_modeUnchanged_noWarning() {
        ChannelConfigService configService = mock(ChannelConfigService.class);
        ChannelAdapterRegistry registry = mock(ChannelAdapterRegistry.class);
        FeishuClient feishuClient = mock(FeishuClient.class);
        TelegramBotClient telegramBotClient = mock(TelegramBotClient.class);
        ChannelConfigController controller = new ChannelConfigController(
                configService, registry, new ObjectMapper(), feishuClient, telegramBotClient);

        ChannelConfigEntity existing = new ChannelConfigEntity();
        existing.setId(2L);
        existing.setPlatform("feishu");
        existing.setDisplayName("Feishu");
        existing.setConfigJson("{\"mode\":\"webhook\"}");
        existing.setDefaultAgentId(100L);
        existing.setWebhookSecret("secret");
        existing.setCredentialsJson("{\"app_id\":\"cli_abc\"}");
        existing.setActive(true);

        when(configService.getById(2L)).thenReturn(Optional.of(existing));
        when(configService.save(any(ChannelConfigEntity.class))).thenReturn(existing);

        ChannelConfigController.ConfigRequest request = new ChannelConfigController.ConfigRequest();
        request.displayName = "Feishu Bot";

        ResponseEntity<?> response = controller.patch(2L, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(ChannelConfigController.ConfigView.class);
        ChannelConfigController.ConfigView body = (ChannelConfigController.ConfigView) response.getBody();
        assertThat(body.warning()).isNull();
    }
}
