package io.kubespawn.platform.provisioning;

import io.kubespawn.platform.worker.JobLease;
import java.util.function.BooleanSupplier;
import static io.kubespawn.platform.provisioning.ProvisioningModels.*;

public interface ProvisioningGateway {
    default Observation validate(Plan plan) { return null; }
    Observation reconcile(Plan plan, JobLease lease, BooleanSupplier current);
    Observation cleanup(String namespace, JobLease lease, BooleanSupplier current);
}
