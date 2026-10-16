package com.campusclaw.repository;

import com.campusclaw.domain.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    /** 会话历史（供追问理解）：按 id 倒序取最近 N 条，调用方再翻正。 */
    List<ChatMessage> findTop6BySessionIdOrderByIdDesc(Long sessionId);
}
