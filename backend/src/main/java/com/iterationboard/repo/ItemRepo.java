package com.iterationboard.repo;

import com.iterationboard.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ItemRepo extends JpaRepository<Item, Long> {

        Optional<Item> findByIdAndTeamId(Long id, Long teamId);
        List<Item> findByTeamId(Long teamId);
        List<Item> findByParentId(Long parentId);
        List<Item> findByBatchId(Long batchId);
        List<Item> findByTeamIdAndType(Long teamId, Enums.ItemType type);
        Optional<Item> findFirstByTeamIdAndExternalSystemAndExternalId(Long teamId, String externalSystem, String externalId);
        List<Item> findByTeamIdAndDeletedAtIsNotNull(Long teamId);
        List<Item> findByDeletedAtBefore(Instant cutoff);
        long countByParentId(Long parentId);
        long countByParentIdAndStatus(Long parentId, Enums.ItemStatus status);
}
