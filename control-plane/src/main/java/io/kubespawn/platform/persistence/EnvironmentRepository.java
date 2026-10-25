package io.kubespawn.platform.persistence;

import io.kubespawn.platform.domain.Environment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {}
