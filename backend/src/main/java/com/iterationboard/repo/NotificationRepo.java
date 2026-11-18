package com.iterationboard.repo;

import com.iterationboard.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationRepo extends JpaRepository<Notification, Long> {
    List<Notification> findByRecipientId(Long recipientId);
    /** M5 留存清理：删除 createdAt 早于界限的通知，返回删除行数。 */
    long deleteByCreatedAtBefore(Instant cutoff);
    long countByRecipientIdAndTypeAndItemId(Long recipientId, String type, Long itemId);

    List<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId);
    List<Notification> findByRecipientIdAndTeamIdOrderByCreatedAtDesc(Long recipientId, Long teamId);
    long countByRecipientIdAndReadAtIsNull(Long recipientId);
    long countByRecipientIdAndTeamIdAndReadAtIsNull(Long recipientId, Long teamId);

    @Query("select n from Notification n where n.recipient.id = :rid and n.type = :type " +
           "and ((:itemId is null and n.item.id is null) or n.item.id = :itemId) and n.createdAt >= :since")
    List<Notification> findRecent(@Param("rid") Long recipientId, @Param("type") String type,
                                  @Param("itemId") Long itemId, @Param("since") Instant since);
}
