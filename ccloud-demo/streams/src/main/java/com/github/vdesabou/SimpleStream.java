package com.github.vdesabou;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.kstream.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import com.github.vdesabou.Customer;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;

/**
 */
public class SimpleStream {

    private static final String TOPIC = "customer-avro";

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, System.getenv("BOOTSTRAP_SERVERS"));
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 3);
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "simple-stream");
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 5 * 1000);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        //props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,StreamsConfig.EXACTLY_ONCE);

        props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3);
        props.put(StreamsConfig.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
        props.put(SaslConfigs.SASL_MECHANISM, "PLAIN");
        props.put(SaslConfigs.SASL_JAAS_CONFIG, "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"" + System.getenv("CLOUD_KEY") + "\" password=\"" + System.getenv("CLOUD_SECRET") + "\";");

        // Recommended performance/resilience settings
        props.put(StreamsConfig.producerPrefix(ProducerConfig.RETRIES_CONFIG), 2147483647);
        props.put("producer.confluent.batch.expiry.ms", 9223372036854775807L);
        props.put(StreamsConfig.producerPrefix(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG), 300000);
        props.put(StreamsConfig.producerPrefix(ProducerConfig.MAX_BLOCK_MS_CONFIG), 9223372036854775807L);

        // Confluent Schema Registry for Java
        props.put("schema.registry.url", System.getenv("SCHEMA_REGISTRY_URL"));
        props.put("basic.auth.credentials.source", System.getenv("BASIC_AUTH_CREDENTIALS_SOURCE"));
        props.put("schema.registry.basic.auth.user.info", System.getenv("SCHEMA_REGISTRY_BASIC_AUTH_USER_INFO"));

        // interceptors for C3
        props.put(StreamsConfig.PRODUCER_PREFIX + ProducerConfig.INTERCEPTOR_CLASSES_CONFIG,"io.confluent.monitoring.clients.interceptor.MonitoringProducerInterceptor");
        props.put(StreamsConfig.CONSUMER_PREFIX + ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG,"io.confluent.monitoring.clients.interceptor.MonitoringConsumerInterceptor");
        props.put("confluent.monitoring.interceptor.bootstrap.servers", System.getenv("BOOTSTRAP_SERVERS"));
        props.put("confluent.monitoring.interceptor.security.protocol", "SASL_SSL");
        props.put("confluent.monitoring.interceptor.sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"" + System.getenv("CLOUD_KEY") + "\" password=\"" + System.getenv("CLOUD_SECRET") + "\";");
        props.put("confluent.monitoring.interceptor.sasl.mechanism", "PLAIN");
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,LogAndContinueExceptionHandler.class.getName());

        final Serde<String> stringSerde = Serdes.String();
        final Serde<Customer> specificAvroSerde = new SpecificAvroSerde<>();
        // Note how we must manually call `configure()` on this serde to configure the schema registry
        // url.  This is different from the case of setting default serdes (see `streamsConfiguration`
        // above), which will be auto-configured based on the `StreamsConfiguration` instance.
        final boolean isKeySerde = false;
        Map<String, Object> SRconfig = new HashMap<>();
        SRconfig.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, System.getenv("SCHEMA_REGISTRY_URL"));
        SRconfig.put("schema.registry.basic.auth.user.info", System.getenv("SCHEMA_REGISTRY_BASIC_AUTH_USER_INFO"));
        SRconfig.put("basic.auth.credentials.source", System.getenv("BASIC_AUTH_CREDENTIALS_SOURCE"));
        specificAvroSerde.configure(
            SRconfig,
            isKeySerde);
        
        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, Customer> records = builder.stream(TOPIC,
                                            Consumed.with(stringSerde, specificAvroSerde));

        KStream<String,Long> counts = records.map((k, v) -> new KeyValue<String, Long>(k, v.getCount()));
        counts.print(Printed.<String,Long>toSysOut().withLabel("Consumed record"));

        // Aggregate values by key
        KStream<String,Long> countAgg = counts.groupByKey(Serialized.with(Serdes.String(), Serdes.Long()))
            .reduce(
                (aggValue, newValue) -> aggValue + newValue)
            .toStream();
        countAgg.print(Printed.<String,Long>toSysOut().withLabel("Running count"));

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();

        // Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }
}
