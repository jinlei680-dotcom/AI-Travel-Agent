package com.aitravel.planner.repo;

import com.aitravel.planner.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    List<Conversation> findByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);
}

