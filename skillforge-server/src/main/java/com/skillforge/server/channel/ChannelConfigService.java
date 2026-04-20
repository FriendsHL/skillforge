package com.skillforge.server.channel;

import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.entity.ChannelConfigEntity;
import com.skillforge.server.repository.ChannelConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Loads and (in future) AES-GCM decrypts channel credentials.
 * TODO: AES-GCM encryption for webhookSecret/credentialsJson — currently plaintext.
 */
@Service
public class ChannelConfigService {

    private final ChannelConfigRepository repo;

    public ChannelConfigService(ChannelConfigRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Optional<ChannelConfigDecrypted> getDecryptedConfig(String platform) {
        return repo.findByPlatform(platform)
                .filter(ChannelConfigEntity::isActive)
                .map(this::toDecrypted);
    }

    @Transactional(readOnly = true)
    public Optional<ChannelConfigEntity> getById(Long id) {
        return repo.findById(id);
    }

    @Transactional(readOnly = true)
    public List<ChannelConfigEntity> listAll() {
        return repo.findAll();
    }

    @Transactional
    public ChannelConfigEntity save(ChannelConfigEntity entity) {
        // TODO: AES-GCM encrypt webhookSecret and credentialsJson before persistence.
        return repo.save(entity);
    }

    @Transactional
    public void deleteById(Long id) {
        repo.deleteById(id);
    }

    private ChannelConfigDecrypted toDecrypted(ChannelConfigEntity e) {
        // TODO: AES-GCM decrypt — currently plaintext.
        return new ChannelConfigDecrypted(
                e.getId(),
                e.getPlatform(),
                e.getWebhookSecret(),
                e.getCredentialsJson(),
                e.getConfigJson(),
                e.getDefaultAgentId()
        );
    }
}
