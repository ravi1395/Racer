package com.cheetah.racer.processor;

import com.cheetah.racer.annotation.RacerPublisher;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.exception.RacerConfigurationException;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the strict-mode alias validation added to
 * {@link RacerPublisherFieldProcessor} in v1.4.0 (item 1.1).
 *
 * <p>Verifies that {@code @RacerPublisher("unknown")} throws a
 * {@link RacerConfigurationException} at bean post-processing time when
 * {@code racer.strict-channel-validation=true}, and that known aliases and
 * the lenient fallback continue to work correctly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerPublisherFieldProcessorStrictModeTest {

    @Mock private ApplicationContext applicationContext;
    @Mock private RacerPublisherRegistry publisherRegistry;
    @Mock private RacerChannelPublisher channelPublisher;

    /** Test bean whose field carries the alias under test. */
    static class BeanWithUnknownAlias {
        @RacerPublisher("does-not-exist")
        private RacerChannelPublisher publisher;
    }

    /** Test bean whose field carries a valid alias. */
    static class BeanWithValidAlias {
        @RacerPublisher("orders")
        private RacerChannelPublisher publisher;

        RacerChannelPublisher getPublisher() { return publisher; }
    }

    /** Test bean with no alias (uses the default channel). */
    static class BeanWithDefaultAlias {
        @RacerPublisher
        private RacerChannelPublisher publisher;

        RacerChannelPublisher getPublisher() { return publisher; }
    }

    // Build RacerProperties with one valid alias and the given strict flag
    private RacerProperties buildProps(boolean strict) {
        RacerProperties props = new RacerProperties();
        props.setDefaultChannel("racer:messages");
        props.setStrictChannelValidation(strict);

        RacerProperties.ChannelProperties orders = new RacerProperties.ChannelProperties();
        orders.setName("racer:orders");
        props.setChannels(Map.of("orders", orders));
        return props;
    }

    private RacerPublisherFieldProcessor buildProcessor(boolean strict) {
        RacerProperties props = buildProps(strict);

        when(applicationContext.getBean(RacerPublisherRegistry.class)).thenReturn(publisherRegistry);
        when(applicationContext.getBean(RacerProperties.class)).thenReturn(props);
        when(publisherRegistry.getPublisher("orders")).thenReturn(channelPublisher);
        when(publisherRegistry.getPublisher("")).thenReturn(channelPublisher);
        when(channelPublisher.getChannelName()).thenReturn("racer:orders");

        RacerPublisherFieldProcessor processor = new RacerPublisherFieldProcessor();
        processor.setApplicationContext(applicationContext);
        return processor;
    }

    // ── Strict mode ──────────────────────────────────────────────────────────

    @Test
    void strictMode_unknownAlias_throwsRacerConfigurationExceptionAtPostProcessTime() {
        // The processor should throw before trying to inject anything, giving a clear
        // startup error that names the bean, the field, and the bad alias.
        RacerPublisherFieldProcessor processor = buildProcessor(true);

        assertThatThrownBy(() ->
                processor.postProcessBeforeInitialization(new BeanWithUnknownAlias(), "beanWithUnknownAlias"))
                .isInstanceOf(RacerConfigurationException.class)
                .hasMessageContaining("does-not-exist")
                .hasMessageContaining("BeanWithUnknownAlias");
    }

    @Test
    void strictMode_errorMessageContainsFieldName() {
        RacerPublisherFieldProcessor processor = buildProcessor(true);

        assertThatThrownBy(() ->
                processor.postProcessBeforeInitialization(new BeanWithUnknownAlias(), "testBean"))
                .isInstanceOf(RacerConfigurationException.class)
                .hasMessageContaining("publisher"); // the field name
    }

    @Test
    void strictMode_knownAlias_injectsPublisherWithoutThrowing() {
        RacerPublisherFieldProcessor processor = buildProcessor(true);
        BeanWithValidAlias bean = new BeanWithValidAlias();

        Object result = processor.postProcessBeforeInitialization(bean, "beanWithValidAlias");

        // Bean is returned unchanged; publisher is injected
        assertThat(result).isSameAs(bean);
        assertThat(bean.getPublisher()).isSameAs(channelPublisher);
    }

    @Test
    void strictMode_blankAlias_usesDefaultChannelWithoutThrowing() {
        // @RacerPublisher with no value (empty string alias) is exempt from strict mode
        // because it explicitly requests the default channel
        RacerPublisherFieldProcessor processor = buildProcessor(true);
        BeanWithDefaultAlias bean = new BeanWithDefaultAlias();

        // Should not throw — blank alias always falls through to the default channel
        Object result = processor.postProcessBeforeInitialization(bean, "beanWithDefault");
        assertThat(result).isSameAs(bean);
    }

    // ── Lenient mode (default) ────────────────────────────────────────────────

    @Test
    void lenientMode_unknownAlias_delegatesToRegistryWithoutPreValidation() {
        // In lenient mode the processor does not check the alias itself;
        // it delegates to the registry which will log a warning and return the default.
        RacerProperties props = buildProps(false);
        when(applicationContext.getBean(RacerProperties.class)).thenReturn(props);
        when(applicationContext.getBean(RacerPublisherRegistry.class)).thenReturn(publisherRegistry);
        when(publisherRegistry.getPublisher("does-not-exist")).thenReturn(channelPublisher);
        when(channelPublisher.getChannelName()).thenReturn("racer:messages");

        RacerPublisherFieldProcessor processor = new RacerPublisherFieldProcessor();
        processor.setApplicationContext(applicationContext);

        BeanWithUnknownAlias bean = new BeanWithUnknownAlias();
        // Should not throw — lenient mode falls back gracefully
        processor.postProcessBeforeInitialization(bean, "lenientBean");
    }
}
