package com.faqrobot.repository;

import com.faqrobot.model.KnowledgeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeRepository extends JpaRepository<KnowledgeItem, Long> {

    List<KnowledgeItem> findByCategory(String category);

    List<KnowledgeItem> findByCategoryContaining(String category);

    List<KnowledgeItem> findByTitleContaining(String keyword);

    List<KnowledgeItem> findByKeywordsContaining(String keyword);

    List<KnowledgeItem> findByParentId(Long parentId);

    long countByCategory(String category);

    long countByParentIdIsNull();
}
