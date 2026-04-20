package com.skillforge.server.channel.registry;

import com.skillforge.server.channel.spi.ChannelAdapter;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChannelAdapterRegistry {

    private final Map<String, ChannelAdapter> adapters;

    public ChannelAdapterRegistry(List<ChannelAdapter> adapterList) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ChannelAdapter::platformId, Function.identity()));
    }

    public Optional<ChannelAdapter> get(String platformId) {
        return Optional.ofNullable(adapters.get(platformId));
    }

    public Set<String> registeredPlatforms() {
        return Collections.unmodifiableSet(adapters.keySet());
    }
}
