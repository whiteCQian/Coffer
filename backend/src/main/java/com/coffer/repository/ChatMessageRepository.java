package com.coffer.repository;

import com.coffer.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 对话消息 Repository。
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 按会话 ID 查询该会话下的所有聊天记录，按时间升序排列。
     *
     * @param sessionId 会话 ID
     * @return 聊天记录列表
     */
    List<ChatMessage> findBySessionIdOrderByTimestampAsc(String sessionId);

    /**
     * 按会话 ID 删除历史消息，用于清理过期会话。
     *
     * <p>派生 deleteBy 查询属于修改操作，JPA 的 executeUpdate 必须在事务内执行，
     * 因此显式标注 @Transactional，避免调用方无事务时抛 TransactionRequiredException。
     *
     * @param sessionId 会话 ID
     */
    @Transactional
    void deleteBySessionId(String sessionId);
}
