/**
 * Remote session server for pi-java.
 *
 * <p>Hosts agent sessions accessible by pi-java-client instances
 * over the CBOR protocol. Full implementation in Phase 6.</p>
 */
module com.pijava.server {
    requires com.pijava.protocol;
    requires com.pijava.agent;

    // exports com.pijava.server; — Phase 6
}
