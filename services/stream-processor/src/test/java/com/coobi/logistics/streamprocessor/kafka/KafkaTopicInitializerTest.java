package com.coobi.logistics.streamprocessor.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coobi.logistics.streamprocessor.config.KafkaTopicsProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** MVP-2.2 and MVP-2.3: topics are created reproducibly and idempotently. */
@ExtendWith(MockitoExtension.class)
class KafkaTopicInitializerTest {

    private static final String LOCATION_TOPIC = "logistics.vehicle.location.v1";
    private static final String DEAD_LETTER_TOPIC = "logistics.vehicle.location.dlq.v1";
    private static final String ALERT_TOPIC = "logistics.alert.v1";

    @Mock
    private Admin admin;

    private KafkaTopicInitializer initializer;

    @BeforeEach
    void setUp() {
        KafkaTopicsProperties properties = new KafkaTopicsProperties();
        properties.getInitialization().setMaxAttempts(2);
        properties.getInitialization().setRetryBackoffMillis(0);
        initializer = new KafkaTopicInitializer(admin, properties);
    }

    @Test
    void createsTheThreeTopicsWithSixPartitionsAndReplicationOne() {
        givenExistingTopics();
        givenTopicCreationSucceeds();

        initializer.ensureTopics();

        List<NewTopic> created = capturedCreatedTopics();
        assertThat(created).extracting(NewTopic::name).containsExactly(
                LOCATION_TOPIC, DEAD_LETTER_TOPIC, ALERT_TOPIC);
        assertThat(created).allSatisfy(topic -> {
            assertThat(topic.numPartitions()).isEqualTo(6);
            assertThat(topic.replicationFactor()).isEqualTo((short) 1);
        });
    }

    @Test
    void onlyCreatesTheTopicsThatAreMissing() {
        givenExistingTopics(LOCATION_TOPIC, DEAD_LETTER_TOPIC);
        givenTopicCreationSucceeds();

        initializer.ensureTopics();

        assertThat(capturedCreatedTopics()).extracting(NewTopic::name).containsExactly(ALERT_TOPIC);
    }

    @Test
    void isIdempotentWhenTheTopicsAlreadyExist() {
        givenExistingTopics(LOCATION_TOPIC, DEAD_LETTER_TOPIC, ALERT_TOPIC);

        initializer.ensureTopics();
        initializer.ensureTopics();

        verify(admin, never()).createTopics(anyCollection());
    }

    @Test
    void acceptsTopicsCreatedByAConcurrentStarter() {
        givenExistingTopics();
        CreateTopicsResult concurrentCreation =
                failingTopicCreation(new ExecutionException(new TopicExistsException("topic exists")));
        when(admin.createTopics(anyCollection())).thenReturn(concurrentCreation);

        initializer.ensureTopics();

        assertThat(capturedCreatedTopics()).hasSize(3);
    }

    @Test
    void failsFastWhenTheBrokerIsUnavailable() {
        KafkaFuture<Set<String>> unavailable = failed(
                new ExecutionException("broker unavailable", new RuntimeException("connection refused")));
        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        when(listTopicsResult.names()).thenReturn(unavailable);
        when(admin.listTopics()).thenReturn(listTopicsResult);

        assertThatThrownBy(initializer::ensureTopics)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attempt 2 of 2");
        verify(admin, times(2)).listTopics();
    }

    @Test
    void skipsProvisioningWhenInitializationIsDisabled() {
        KafkaTopicsProperties properties = new KafkaTopicsProperties();
        properties.getInitialization().setEnabled(false);

        new KafkaTopicInitializer(admin, properties).ensureTopics();

        verify(admin, never()).listTopics();
    }

    /** The broker already holds the given topics. */
    private void givenExistingTopics(String... names) {
        KafkaFuture<Set<String>> existingNames = completed(Set.of(names));
        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        when(listTopicsResult.names()).thenReturn(existingNames);
        when(admin.listTopics()).thenReturn(listTopicsResult);
    }

    private void givenTopicCreationSucceeds() {
        KafkaFuture<Void> created = completed((Void) null);
        CreateTopicsResult createTopicsResult = mock(CreateTopicsResult.class);
        when(createTopicsResult.all()).thenReturn(created);
        when(admin.createTopics(anyCollection())).thenReturn(createTopicsResult);
    }

    private static CreateTopicsResult failingTopicCreation(Throwable cause) {
        KafkaFuture<Void> failure = failed(cause);
        CreateTopicsResult createTopicsResult = mock(CreateTopicsResult.class);
        when(createTopicsResult.all()).thenReturn(failure);
        return createTopicsResult;
    }

    @SuppressWarnings("unchecked")
    private List<NewTopic> capturedCreatedTopics() {
        ArgumentCaptor<Collection<NewTopic>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(admin).createTopics(captor.capture());
        return new ArrayList<>(captor.getValue());
    }

    @SuppressWarnings("unchecked")
    private static <T> KafkaFuture<T> completed(T value) {
        KafkaFuture<T> future = mock(KafkaFuture.class);
        try {
            when(future.get(anyLong(), any())).thenReturn(value);
        } catch (Exception failure) {
            throw new IllegalStateException("stubbing a completed kafka future failed", failure);
        }
        return future;
    }

    @SuppressWarnings("unchecked")
    private static <T> KafkaFuture<T> failed(Throwable cause) {
        KafkaFuture<T> future = mock(KafkaFuture.class);
        try {
            when(future.get(anyLong(), any())).thenThrow(cause);
        } catch (Exception failure) {
            throw new IllegalStateException("stubbing a failed kafka future failed", failure);
        }
        return future;
    }
}
