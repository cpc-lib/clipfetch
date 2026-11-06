package cc.ivera.ed2k.domain;

public enum Ed2kActionState {
    PENDING,
    APPLYING,
    APPLIED,
    RETRY_WAIT,
    SUPERSEDED,
    FAILED
}
