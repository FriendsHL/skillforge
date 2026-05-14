package com.skillforge.server.repository;

import com.skillforge.server.entity.ChatAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachmentEntity, String> {

    List<ChatAttachmentEntity> findBySessionIdAndIdIn(String sessionId, Collection<String> ids);
}
