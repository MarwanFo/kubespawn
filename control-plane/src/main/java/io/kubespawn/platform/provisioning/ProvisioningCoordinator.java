package io.kubespawn.platform.provisioning;

import io.kubespawn.platform.worker.JobLease;
import io.kubespawn.platform.worker.JobProcessor;
import org.springframework.stereotype.Service;

@Service
public class ProvisioningCoordinator {
    private final ProvisioningSettings settings;
    private final ProvisioningStore store;
    private final ProvisioningGateway gateway;
    public ProvisioningCoordinator(ProvisioningSettings settings,ProvisioningStore store,ProvisioningGateway gateway) {
        this.settings=settings; this.store=store; this.gateway=gateway;
    }
    public JobProcessor.Decision reconcile(JobLease lease) {
        if (!settings.enabled) return new JobProcessor.Decision("KUBERNETES_INTEGRATION_REQUIRED","Build verified. Enable the local Kubernetes integration to provision this request.");
        var plan=store.plan(lease.environmentId());
        if (plan.isEmpty()) return new JobProcessor.Decision("BUILD_VERIFICATION_REQUIRED","Waiting for current approved build evidence.");
        var blocked=gateway.validate(plan.get());
        if (blocked!=null) return new JobProcessor.Decision(blocked.stage(),blocked.message());
        if (!store.reserve(lease)) return new JobProcessor.Decision("CAPACITY_WAIT","Waiting for a local cluster capacity slot.");
        var result=gateway.reconcile(plan.get(),lease,()->store.current(lease,true) && store.plan(lease.environmentId()).filter(plan.get()::equals).isPresent());
        return new JobProcessor.Decision(result.stage(),result.message(),null,null,null,result);
    }
    public boolean hasVerifiedBuild(JobLease lease) { return store.plan(lease.environmentId()).isPresent(); }
    public JobProcessor.Decision cleanup(JobLease lease) {
        if (!settings.enabled) return new JobProcessor.Decision("CLEANUP_VERIFICATION_REQUIRED","Enable the local Kubernetes integration to verify resource removal.");
        var result=gateway.cleanup(store.namespace(lease.environmentId()),lease,()->store.current(lease,false));
        return new JobProcessor.Decision(result.stage(),result.message(),null,null,null,result);
    }
}
