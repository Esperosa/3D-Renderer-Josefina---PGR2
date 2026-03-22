package engine.render.ray.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves and validates production runtime package location for denoiser metadata.
 */
public final class DenoiserRuntimePackageBridge {

    private static final String PROPERTY_ROOT = "app.denoiser.runtime.root";
    private static final String ENV_ROOT = "APP_DENOISER_RUNTIME_ROOT";
    private static final String DEFAULT_ROOT = "runtime/denoiser-package";

    private static final String[] REQUIRED_FILES = new String[] {
        "java_weights/weights_manifest.json",
        "inference_spec.md",
        "runtime_orchestrator_design.md",
        "runtime_policy_release_hardened.md"
    };

    private DenoiserRuntimePackageBridge() {
    }

    public static PackageStatus resolve(String overrideRoot, boolean required) {
        RootSelection selection = resolveRootSelection(overrideRoot);
        Path root = Paths.get(selection.rootValue).normalize();
        List<String> missing = collectMissingEntries(root);
        boolean ready = missing.isEmpty();
        String status = ready ? "READY" : (required ? "MISSING_REQUIRED" : "MISSING_OPTIONAL");
        return new PackageStatus(ready, required, root, selection.source, status, Collections.unmodifiableList(missing));
    }

    private static RootSelection resolveRootSelection(String overrideRoot) {
        String override = clean(overrideRoot);
        if (!override.isEmpty()) {
            return new RootSelection(override, "renderer-override");
        }

        String property = clean(System.getProperty(PROPERTY_ROOT));
        if (!property.isEmpty()) {
            return new RootSelection(property, "system-property");
        }

        String env = clean(System.getenv(ENV_ROOT));
        if (!env.isEmpty()) {
            return new RootSelection(env, "environment");
        }

        return new RootSelection(DEFAULT_ROOT, "default");
    }

    private static List<String> collectMissingEntries(Path root) {
        List<String> missing = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            missing.add("<root-directory>");
            return missing;
        }
        for (String relative : REQUIRED_FILES) {
            if (!Files.isRegularFile(root.resolve(relative))) {
                missing.add(relative);
            }
        }
        return missing;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class RootSelection {
        private final String rootValue;
        private final String source;

        private RootSelection(String rootValue, String source) {
            this.rootValue = rootValue;
            this.source = source;
        }
    }

    public static final class PackageStatus {
        private final boolean ready;
        private final boolean required;
        private final Path root;
        private final String source;
        private final String status;
        private final List<String> missingEntries;

        private PackageStatus(boolean ready,
                              boolean required,
                              Path root,
                              String source,
                              String status,
                              List<String> missingEntries) {
            this.ready = ready;
            this.required = required;
            this.root = root;
            this.source = source;
            this.status = status;
            this.missingEntries = missingEntries;
        }

        public boolean ready() {
            return ready;
        }

        public boolean required() {
            return required;
        }

        public Path root() {
            return root;
        }

        public String source() {
            return source;
        }

        public String status() {
            return status;
        }

        public List<String> missingEntries() {
            return missingEntries;
        }

        public String summary() {
            if (ready) {
                return "ready(root=" + root + ", source=" + source + ")";
            }
            return status + "(root=" + root + ", source=" + source + ", missing=" + missingEntries + ")";
        }
    }
}

