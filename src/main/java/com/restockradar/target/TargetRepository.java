package com.restockradar.target;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TargetRepository extends JpaRepository<Target, Long> {

    List<Target> findByActiveTrueAndNextCheckAtBefore(Instant now);

    List<Target> findAllByOrderByIdAsc();
}
