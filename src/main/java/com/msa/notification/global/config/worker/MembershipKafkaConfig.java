package com.msa.notification.global.config.worker;

import com.msa.notification.domain.notification.exception.NotificationException;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.HashMap;

/** 회원제 알림 토픽만 세 번 재시도 후 DLT로 보내며 DLT 송신 실패는 컨테이너를 정지해 무한 루프를 막습니다. */
@Configuration
@ConditionalOnProperty(name = "notification.membership-consumer-enabled", havingValue = "true")
public class MembershipKafkaConfig {
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]>
            membershipKafkaListenerContainerFactory(
                    ConsumerFactory<?, ?> consumerFactory,
                    ObjectProvider<KafkaListenerEndpointRegistry> registry) {
        var settings = new HashMap<String, Object>(consumerFactory.getConfigurationProperties());
        // Notification에는 기존 producer가 없으므로 DLT 전용 producer만 같은 브로커로 만듭니다.
        var producer = new HashMap<String, Object>();
        producer.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                settings.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        producer.put(ProducerConfig.ACKS_CONFIG, "all");
        producer.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producer.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        producer.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 8000);
        producer.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
        var dlt =
                new KafkaTemplate<>(
                        new DefaultKafkaProducerFactory<>(
                                producer, new StringSerializer(), new ByteArraySerializer()));
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        dlt,
                        (record, failure) ->
                                new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        var handler =
                new DefaultErrorHandler(
                        (record, failure) -> {
                            try {
                                recoverer.accept(record, failure);
                            } catch (RuntimeException sendFailure) {
                                // DLT ack가 없으면 offset을 넘기지 않고 운영 복구 때 같은 이벤트를 받습니다.
                                var container =
                                        registry.getObject()
                                                .getListenerContainer("membership-notices");
                                if (container != null) container.stop(() -> {});
                                throw sendFailure;
                            }
                        },
                        new FixedBackOff(1000L, 3));
        handler.addNotRetryableExceptions(NotificationException.class);
        settings.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        settings.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 16384);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(
                new DefaultKafkaConsumerFactory<>(
                        settings, new StringDeserializer(), new ByteArrayDeserializer()));
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(handler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
