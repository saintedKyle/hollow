package hollow.harness;

/** A JVM that does nothing for ten minutes. The attach target for the test. */
public final class Sleep {

    public static void main(String[] args) throws Exception {
        System.out.println("harness pid " + ProcessHandle.current().pid());
        System.out.flush();
        Thread.sleep(600_000L);
    }

    private Sleep() {
    }
}
