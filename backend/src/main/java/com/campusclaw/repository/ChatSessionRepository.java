package com.campusclaw.repository;

import com.campusclaw.domain.ChatSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /** 会话访问一律带归属用户条件：他人/他班会话查不到（对外 404，与不存在一致）。 */
    Optional<ChatSession> findByIdAndOwnerId(Long id, Long ownerId);

    List<ChatSession> findByOwnerIdOrderByIdDesc(Long ownerId);

    /** 会话列表按最近更新时间倒序。 */
    List<ChatSession> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    /** 本人会话的消息数（列表页展示用）。 */
    @Query("select m.sessionId, count(m) from ChatMessage m "
            + "where m.sessionId in (select s.id from ChatSession s where s.ownerId = :ownerId) "
            + "group by m.sessionId")
    List<Object[]> countMessagesByOwner(@Param("ownerId") Long ownerId);
}
