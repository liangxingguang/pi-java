/**
 * CBOR-based protocol for pi-java remote sessions.
 *
 * <p>Defines the wire format and framing used between pi-java-client
 * and pi-java-server. Full implementation in Phase 6.</p>
 */
module com.pijava.protocol {
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.dataformat.cbor;

    // exports com.pijava.protocol; — Phase 6
}
