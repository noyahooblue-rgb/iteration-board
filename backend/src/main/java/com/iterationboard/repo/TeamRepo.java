package com.iterationboard.repo;

import com.iterationboard.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface TeamRepo extends JpaRepository<Team, Long> {

        Optional<Team> findByInviteCode(String inviteCode);
        /** 小组代号全平台唯一校验（不区分大小写）。 */
        boolean existsByCodeIgnoreCase(String code);
        /** 行锁取组行，保证组内事项序号并发唯一。 */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select t from Team t where t.id = :id")
        Optional<Team> findByIdForUpdate(@Param("id") Long id);
}
