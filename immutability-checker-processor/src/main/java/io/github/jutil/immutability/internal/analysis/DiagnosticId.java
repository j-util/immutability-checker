package io.github.jutil.immutability.internal.analysis;

enum DiagnosticId {
    ANALYSIS_UNAVAILABLE("IC000"),
    UNSUPPORTED_ANNOTATED_TYPE("IC001"),
    ENCLOSING_STATE_UNPROVEN("IC002"),
    INHERITED_STATE_UNPROVEN("IC003"),
    EXTERNALLY_WRITABLE_FIELD("IC004"),
    REACHABLE_REFERENCE_UNPROVEN("IC005"),
    POST_FREEZE_WRITE("IC006");

    private final String code;

    DiagnosticId(String code) {
        this.code = code;
    }

    String prefix() {
        return "[" + code + "]";
    }
}
