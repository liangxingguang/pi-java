package com.pijava.codemode.backend;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.codemode.api.CodeModeData;
import com.pijava.codemode.api.CodeModeVmLauncher;
import com.pijava.codemode.api.Protocol;

/** Starts each codemode script in its own Endive-backed QuickJS instance. */
public final class EndiveCodeModeLauncher implements CodeModeVmLauncher {
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();

    /** Creates a launcher. */
    public EndiveCodeModeLauncher() {
    }

    @Override
    public VmChannel launch(CodeModeData.StartData data, VmEvents events) throws Exception {
        Channel channel = new Channel(events);
        Thread thread = new Thread(() -> run(data, channel), "codemode-vm-" + THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        channel.thread = thread;
        thread.start();
        return channel;
    }

    private static void run(CodeModeData.StartData data, Channel channel) {
        try (EndiveVm vm = new EndiveVm(data, channel.events)) {
            channel.vm = vm;
            while (!channel.stopped.get() && !vm.isStopped()) {
                Protocol.HostToVm message = channel.inbox.poll();
                if (message != null) {
                    vm.post(message);
                    vm.pump();
                    continue;
                }
                if (vm.isDone()) {
                    return;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
            }
        } catch (Throwable failure) {
            channel.events.failure(CodeModeData.CodemodeError.of("vm", failure.toString()));
            channel.events.message(new Protocol.Crash(failure.toString()));
        }
    }

    private static final class Channel implements VmChannel {
        private final VmEvents events;
        private final ConcurrentLinkedQueue<Protocol.HostToVm> inbox = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile Thread thread;
        private volatile EndiveVm vm;

        private Channel(VmEvents events) {
            this.events = events;
        }

        @Override
        public void post(Protocol.HostToVm message) {
            if (!stopped.get()) {
                inbox.add(message);
            }
        }

        @Override
        public void stop() {
            stopped.set(true);
            EndiveVm current = vm;
            if (current != null) {
                current.stop();
            }
            Thread currentThread = thread;
            if (currentThread != null) {
                currentThread.interrupt();
            }
        }
    }
}
