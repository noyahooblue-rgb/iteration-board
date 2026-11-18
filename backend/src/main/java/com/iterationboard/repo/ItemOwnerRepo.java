package com.iterationboard.repo;

import com.iterationboard.domain.ItemOwner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemOwnerRepo extends JpaRepository<ItemOwner, Long> {
    List<ItemOwner> findByItemId(Long itemId);
    List<ItemOwner> findByUserId(Long userId);
    Optional<ItemOwner> findByItemIdAndUserId(Long itemId, Long userId);
    boolean existsByItemIdAndUserId(Long itemId, Long userId);
    void deleteByItemId(Long itemId);
}
