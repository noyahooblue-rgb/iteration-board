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

    // ---- P2c 裁定（zu5skyzg）「当前视图」口径：当前团队通知 + 平台通知（team 为空） ----
    // 铃铛面板条目与未读数共用同一组谓词，保证 角标 == 面板未读 恒等。
    @Query("select n from Notification n where n.recipient.id = :rid " +
           "and (n.team.id = :tid or n.team is null) order by n.createdAt desc")
    List<Notification> findCurrentView(@Param("rid") Long recipientId, @Param("tid") Long teamId);

    @Query("select count(n) from Notification n where n.recipient.id = :rid and n.readAt is null " +
           "and (n.team.id = :tid or n.team is null)")
    long countCurrentUnread(@Param("rid") Long recipientId, @Param("tid") Long teamId);

    /** 团队切换器口径：每个团队各自的未读数（仅团队通知，不含平台通知）。 */
    @Query("select n.team.id, count(n) from Notification n where n.recipient.id = :rid " +
           "and n.readAt is null and n.team is not null group by n.team.id")
    List<Object[]> countUnreadByTeam(@Param("rid") Long recipientId);

    long countByRecipientIdAndTeamIsNullAndReadAtIsNull(Long recipientId);
}
