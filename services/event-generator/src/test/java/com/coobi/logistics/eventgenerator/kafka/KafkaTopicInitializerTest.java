package com.coobi.logistics.eventgenerator.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coobi.logistics.eventgenerator.config.KafkaTopicsProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreatePartitionsResult;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** MVP-1.3: topics are created reproducibly, idempotently and with six partitions. */
@ExtendWith(MockitoExtension.class)
class KafkaTopicInitializerTest {

    private static final String LOCATION_TOPIC = "logistics.vehicle.location.v1";
    private static final String DEAD_LETTER_TOPIC = "logistics.vehicle.location.dlq.v1";
    private static final int EXPECTED_PARTITIONS = 6;

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
    void createsBothTopicsWithSixPartitionsAndReplicationOne() {
        givenTopics();
        givenPartitionCounts(EXPECTED_PARTITIONS, EXPECTED_PARTITIONS);
        givenTopicCreationSucceeds();

        initializer.ensureTopics();

        List<NewTopic> created = capturedCreatedTopics();
        assertThat(created).extracting(NewTopic::name).containsExactly(LOCATION_TOPIC, DEAD_LETTER_TOPIC);
        assertThat(created).allSatisfy(topic -> {
            assertThat(topic.numPartitions()).isEqualTo(EXPECTED_PARTITIONS);
            assertThat(topic.replicationFactor()).isEqualTo((short) 1);
        });
        verify(admin, never()).createPartitions(anyMap());
    }

    @Test
    void isIdempotentWhenTheTopicsAlreadyExist() {
        givenTopics(LOCATION_TOPIC, DEAD_LETTER_TOPIC);
        givenPartitionCounts(EXPECTED_PARTITIONS, EXPECTED_PARTITIONS);

        initializer.ensureTopics();
        initializer.ensureTopics();

        verify(admin, never()).createTopics(anyCollection());
        verify(admin, never()).createPartitions(anyMap());
    }

    @Test
    void expandsATopicThatHasTooFewPartitions() {
        givenTopics(LOCATION_TOPIC, DEAD_LETTER_TOPIC);
        givenPartitionCounts(3, EXPECTED_PARTITIONS);
        givenPartitionExpansionSucceeds();

        initializer.ensureTopics();

        ArgumentCaptor<Map<String, NewPartitions>> expansions = expansionCaptor();
        verify(admin).createPartitions(expansions.capture());
        assertThat(expansions.getValue()).containsOnlyKeys(LOCATION_TOPIC);
        assertThat(expansions.getValue().get(LOCATION_TOPIC).totalCount()).isEqualTo(EXPECTED_PARTITIONS);
        verify(admin, never()).createTopics(anyCollection());
    }

    @Test
    void acceptsATopicThatHasMorePartitionsThanConfigured() {
        givenTopics(LOCATION_TOPIC, DEAD_LETTER_TOPIC);
        givenPartitionCounts(12, EXPECTED_PARTITIONS);

        initializer.ensureTopics();

        verify(admin, never()).createPartitions(anyMap());
        verify(admin, never()).createTopics(anyCollection());
    }

    @Test
    void acceptsTopicsCreatedByAConcurrentStarter() {
        givenTopics();
        givenPartitionCounts(EXPECTED_PARTITIONS, EXPECTED_PARTITIONS);
        // KafkaFuture#get wraps the api error, exactly as a real broker response does.
        CreateTopicsResult concurrentCreation =
                failingTopicCreation(new ExecutionException(new TopicExistsException("topic exists")));
        when(admin.createTopics(anyCollection())).thenReturn(concurrentCreation);

        initializer.ensureTopics();

        assertThat(capturedCreatedTopics()).hasSize(2);
    }

    @Test
    void failsFastWhenTheBrokerIsUnavailable() {
        KafkaFuture<Set<String>> unavailable = failed(new ExecutionException(
                "broker unavailable", new RuntimeException("connection refused")));
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
    private void givenTopics(String... names) {
        KafkaFuture<Set<String>> existingNames = completed(Set.of(names));
        ListTopicsResult listTopicsResult = mock(ListTopicsResult.class);
        when(listTopicsResult.names()).thenReturn(existingNames);
        when(admin.listTopics()).thenReturn(listTopicsResult);
    }

    /** Describing the two topics reports the given partition counts. */
    private void givenPartitionCounts(int locationPartitions, int deadLetterPartitions) {
        Map<String, TopicDescription> descriptions = new LinkedHashMap<>();
        descriptions.put(LOCATION_TOPIC, description(locationPartitions));
        descriptions.put(DEAD_LETTER_TOPIC, description(deadLetterPartitions));
        KafkaFuture<Map<String, TopicDescription>> describedTopics = completed(descriptions);
        DescribeTopicsResult describeTopicsResult = mock(DescribeTopicsResult.class);
        when(describeTopicsResult.allTopicNames()).thenReturn(describedTopics);
        when(admin.describeTopics(anyCollection())).thenReturn(describeTopicsResult);
    }

    private void givenTopicCreationSucceeds() {
        KafkaFuture<Void> created = completed((Void) null);
        CreateTopicsResult createTopicsResult = mock(CreateTopicsResult.class);
        when(createTopicsResult.all()).thenReturn(created);
        when(admin.createTopics(anyCollection())).thenReturn(createTopicsResult);
    }

    private void givenPartitionExpansionSucceeds() {
        KafkaFuture<Void> expanded = completed((Void) null);
        CreatePartitionsResult createPartitionsResult = mock(CreatePartitionsResult.class);
        when(createPartitionsResult.all()).thenReturn(expanded);
        when(admin.createPartitions(anyMap())).thenReturn(createPartitionsResult);
    }

    private static TopicDescription description(int partitions) {
        List<TopicPartitionInfo> partitionInfos = Collections.nCopies(partitions, null);
        TopicDescription description = mock(TopicDescription.class);
        when(description.partitions()).thenReturn(partitionInfos);
        return description;
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
    private static ArgumentCaptor<Map<String, NewPartitions>> expansionCaptor() {
        return ArgumentCaptor.forClass(Map.class);
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
