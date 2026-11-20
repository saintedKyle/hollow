package hollow.injector;

import com.sun.tools.attach.VirtualMachine;

import java.nio.file.Path;

/** The one call the whole tool is built around. */
final class Attach {

    /**
     * Loads hollow-core into the target JVM and blocks until its agentmain
     * returns. The target keeps running either way.
     */
    static void loadAgent(Path agentJar, long pid, String options) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
        try {
            vm.loadAgent(agentJar.toAbsolutePath().toString(), options);
        } finally {
            try {
                vm.detach();
            } catch (Exception ignored) {
                // detaching a dead connection is not an error worth surfacing
            }
        }
    }

    private Attach() {
    }
}
