package com.skillforge.server.channel.spi;

/**
 * Connector SPI for channel push mode (websocket / long-polling).
 * Implementations are lifecycle-managed by ChannelPushManager.
 */
public interface ChannelPushConnector {

    /** Platform id, e.g. "feishu". */
    String platformId();

    /** Start connector with decrypted platform config. */
    void start(ChannelConfigDecrypted config);

    /** Stop connector and release resources. */
    void stop();
}
