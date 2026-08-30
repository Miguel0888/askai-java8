package com.aresstack.askai.localruntime;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertTrue;

/**
 * The watchdog blocks on the parent pipe and only ACTS on EOF — while the parent lives (pipe
 * open, nothing to read), the thread just waits. The exit path itself cannot run in a unit test
 * (it would kill the JVM); this pins the waiting/EOF-detection contract.
 */
public class ParentDeathWatchdogTest {

    /** A pipe that stays OPEN (blocks) until released, then reports EOF. */
    private static final class BlockingThenEofStream extends InputStream {
        private final Object lock = new Object();
        private boolean released;

        @Override
        public int read() throws IOException {
            synchronized (lock) {
                while (!released) {
                    try {
                        lock.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                }
            }
            return -1; // EOF: the parent died
        }

        void release() {
            synchronized (lock) {
                released = true;
                lock.notifyAll();
            }
        }
    }

    @Test
    public void theWatchdogWaitsWhileTheParentLives() throws Exception {
        BlockingThenEofStream pipe = new BlockingThenEofStream();
        Thread watchdog = ParentDeathWatchdog.watch("test", pipe);
        Thread.sleep(150);
        assertTrue("no EOF yet → the watchdog thread keeps waiting", watchdog.isAlive());
        assertTrue("it is a daemon — it never keeps a clean shutdown alive", watchdog.isDaemon());
        // Do NOT release the pipe (EOF would System.exit this test JVM) and do NOT interrupt
        // (the watchdog reads an interrupt as a broken pipe = parent death, by design). The
        // daemon thread parks harmlessly until the test JVM ends.
    }
}
