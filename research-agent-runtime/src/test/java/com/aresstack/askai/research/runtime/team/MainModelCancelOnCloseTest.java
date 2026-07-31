package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.inference.InferenceConfigurationDocument;
import com.aresstack.askai.research.runtime.inference.InferenceConfigurationLoader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Cancel-on-close for the main-model transport: a blocked {@code /api/chat} call aborts promptly when the
 * client is cancelled (a session/tab close, or a pause/cancel) instead of waiting out the full model timeout,
 * and surfaces an honest non-OK result — never a fabricated answer. The host then drops any late delivery via
 * its {@code disposed} guard.
 */
public class MainModelCancelOnCloseTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aBlockedModelCallAbortsWellWithinTheTimeoutWhenCancelled() throws Exception {
        MockMainModelServer mock = new MockMainModelServer();
        try {
            CountDownLatch gate = new CountDownLatch(1);
            mock.blockResponses(gate); // the server holds the response open (a slow/blocking model)
            mock.enqueueMessage("this answer must never be waited for");

            // The descriptor's timeout is 120s; the abort must be far faster than that.
            InferenceConfigurationDocument document = InferenceConfigurationLoader.load(
                    mock.writeInferenceConfig(folder.newFile("inference-config.json"), "gemma4:e2b")
                            .getAbsolutePath());
            final HttpMainModelChatClient client = new HttpMainModelChatClient(document.descriptor);

            final List<ChatMessage> messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("s"));
            messages.add(ChatMessage.user("hello"));
            final AtomicReference<MainModelChatResult> result = new AtomicReference<MainModelChatResult>();
            Thread caller = new Thread(new Runnable() {
                public void run() {
                    result.set(client.complete(messages, 0.4, 256));
                }
            }, "model-call");
            caller.start();
            Thread.sleep(400); // let the call reach and block on the server

            long start = System.currentTimeMillis();
            client.cancelInFlight(); // a close/cancel aborts the in-flight request
            caller.join(5000);
            long elapsed = System.currentTimeMillis() - start;

            assertFalse("the blocked call returned after the abort", caller.isAlive());
            assertTrue("aborted well within the 120s model timeout (was " + elapsed + "ms)", elapsed < 3000);
            assertNotNull(result.get());
            assertFalse("an aborted call is an honest non-OK result, never a fabricated answer",
                    result.get().isOk());

            gate.countDown(); // release the server so it can shut down cleanly
        } finally {
            mock.close();
        }
    }
}
