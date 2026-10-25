package io.kubespawn.platform.api;

import io.kubespawn.platform.persistence.EnvironmentRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnvironmentService {
    private final EnvironmentRepository repository;

    public EnvironmentService(EnvironmentRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<EnvironmentSummary> list(int limit) {
        return repository.findAll(PageRequest.of(0, limit,
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
            .map(EnvironmentSummary::from).getContent();
    }
}
