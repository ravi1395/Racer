package com.cheetah.racer.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Spring Test {@link TestExecutionListener} that makes {@link RacerTest#channels()}
 * functional by registering the declared aliases into the {@link InMemoryRacerPublisherRegistry}
 * before the first test in each test class runs.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Spring Boot boots the application context (driven by {@link RacerTest @RacerTest} /
 *       {@code @SpringBootTest}).</li>
 *   <li>{@link RacerTestAutoConfiguration} registers an {@link InMemoryRacerPublisherRegistry}
 *       populated from {@code application.properties} channels.</li>
 *   <li>This listener's {@link #beforeTestClass} fires; it reads
 *       {@code @RacerTest(channels = {...})} from the test class and calls
 *       {@link InMemoryRacerPublisherRegistry#registerChannel(String)} for each alias not
 *       yet registered by the auto-configuration.</li>
 * </ol>
 *
 * <h3>Registration</h3>
 * <p>This listener is registered automatically via the {@code @TestExecutionListeners}
 * annotation on {@link RacerTest}.  Application code does not need to reference it directly.
 *
 * <h3>Idempotency</h3>
 * <p>If a channel alias declared in {@link RacerTest#channels()} is already present in the
 * registry (e.g. it was also configured in {@code application.properties}), the listener
 * skips it.  Registration is therefore safe to call multiple times.
 *
 * @see RacerTest
 * @see InMemoryRacerPublisherRegistry#registerChannel(String)
 */
public class RacerTestExecutionListener implements TestExecutionListener {

    private static final Logger log =
            LoggerFactory.getLogger(RacerTestExecutionListener.class);

    /**
     * Registers any channels declared in {@link RacerTest#channels()} into the
     * {@link InMemoryRacerPublisherRegistry} bean before any test in the class runs.
     *
     * <p>This hook fires once per test class, after the Spring application context has
     * been fully initialized, so the registry bean is guaranteed to be available.
     *
     * @param testContext the Spring test context for the test class
     */
    @Override
    public void beforeTestClass(TestContext testContext) {
        // Read the @RacerTest annotation from the test class (may be inherited).
        RacerTest racerTest = testContext.getTestClass().getAnnotation(RacerTest.class);
        if (racerTest == null || racerTest.channels().length == 0) {
            // Nothing to register — either annotation is absent or no extra channels declared.
            return;
        }

        // Retrieve the registry from the already-initialized application context.
        InMemoryRacerPublisherRegistry registry;
        try {
            registry = testContext.getApplicationContext()
                    .getBean(InMemoryRacerPublisherRegistry.class);
        } catch (Exception ex) {
            log.warn("[RACER-TEST] Could not find InMemoryRacerPublisherRegistry in context "
                    + "— @RacerTest.channels() will not be registered. Cause: {}",
                    ex.getMessage());
            return;
        }

        // Register each alias declared in @RacerTest(channels = {...}).
        for (String alias : racerTest.channels()) {
            registry.registerChannel(alias);
        }
    }
}
