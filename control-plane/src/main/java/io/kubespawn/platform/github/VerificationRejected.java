package io.kubespawn.platform.github;

public class VerificationRejected extends RuntimeException {
    private final String code;
    public VerificationRejected(String code) { super("GitHub build evidence was rejected."); this.code=code; }
    public String code() { return code; }
}
