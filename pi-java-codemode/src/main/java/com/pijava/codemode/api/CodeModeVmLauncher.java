package com.pijava.codemode.api;

/** Starts one VM for one script execution. */
@FunctionalInterface
public interface CodeModeVmLauncher {
    /** Starts a VM; the VM may report messages before this method returns. */
    VmChannel launch(CodeModeData.StartData data, VmEvents events) throws Exception;

    /** Callbacks a launcher uses to report VM activity. */
    interface VmEvents {
        /** Reports a message from the VM. */
        void message(Protocol.VmToHost message);

        /** Reports a failure outside the bridge. */
        void failure(CodeModeData.CodemodeError error);
    }

    /** The host's handle on a running VM. */
    interface VmChannel {
        /** Delivers an answer to a script call. */
        void post(Protocol.HostToVm message);

        /** Requests VM shutdown; may return before the VM has exited. */
        void stop();
    }
}
