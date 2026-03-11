public final class GltfFeatureAudit {

    private static final String DELEGATE_CLASS = "engine.tools.GltfFeatureAudit";

    private GltfFeatureAudit() {
    }

    public static void main(String[] args) {
        try {
            Class<?> auditClass = Class.forName(DELEGATE_CLASS);
            auditClass.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Failed to start " + DELEGATE_CLASS, ex);
        }
    }
}
