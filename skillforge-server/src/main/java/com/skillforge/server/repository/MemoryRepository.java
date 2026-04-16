package com.skillforge.server.repository;

import com.skillforge.server.entity.MemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MemoryRepository extends JpaRepository<MemoryEntity, Long> {

    List<MemoryEntity> findByUserId(Long userId);

    List<MemoryEntity> findByUserIdAndType(Long userId, String type);

    List<MemoryEntity> findByUserIdAndContentContaining(Long userId, String keyword);

    List<MemoryEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<MemoryEntity> findByUserIdAndTitle(Long userId, String title);

    @Modifying
    @Query("UPDATE MemoryEntity m SET m.recallCount = m.recallCount + 1, m.lastRecalledAt = :now WHERE m.id = :id")
    void incrementRecallCount(@Param("id") Long id, @Param("now") Instant now);

    // FTS recall: tsvector @@ plainto_tsquery ('simple' dictionary, Chinese/English compatible)
    @Query(value = """
        SELECT id, type, title, content, tags, recall_count,
               ts_rank(search_vector, plainto_tsquery('simple', :query)) AS rank
        FROM t_memory
        WHERE user_id = :userId
          AND search_vector @@ plainto_tsquery('simple', :query)
        ORDER BY rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findByFts(
            @Param("userId") Long userId,
            @Param("query") String query,
            @Param("limit") int limit);

    // Vector recall: cosine distance (<=> lower is closer)
    @Query(value = """
        SELECT id, type, title, content, tags, recall_count,
               (embedding <=> CAST(:embedding AS vector)) AS distance
        FROM t_memory
        WHERE user_id = :userId
          AND embedding IS NOT NULL
        ORDER BY distance ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findByVector(
            @Param("userId") Long userId,
            @Param("embedding") String embedding,
            @Param("limit") int limit);

    // Async update embedding column
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE t_memory SET embedding = CAST(:embedding AS vector) WHERE id = :id",
           nativeQuery = true)
    void updateEmbedding(@Param("id") Long id, @Param("embedding") String embedding);
}
