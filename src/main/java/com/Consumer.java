package com;

import com.rabbitmq.client.*;
import proto.Messages.Historical;
import proto.Messages.HistoricalValue;
import proto.Messages.Universal;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Consumer {

	public static void main(String[] args) throws InterruptedException {
		int consumerCount = 2;
		String queueName = "hello";
		String host = "localhost";

		RabbitMqConsumerManager consumerManager = new RabbitMqConsumerManager(queueName, host, consumerCount);
		consumerManager.startConsumers();
	}
}

class RabbitMqConsumerManager {
	private static final Logger logger = LoggerFactory.getLogger(RabbitMqConsumerManager.class);
	private final String queueName;
	private final String host;
	private final int consumerCount;
	private final ExecutorService executorService;

	public RabbitMqConsumerManager(String queueName, String host, int consumerCount) {
		this.queueName = queueName;
		this.host = host;
		this.consumerCount = consumerCount;
		this.executorService = Executors.newFixedThreadPool(consumerCount);
	}

	public void startConsumers() throws InterruptedException {
		ConnectionFactory factory = new ConnectionFactory();
		factory.setHost(host);
		factory.setAutomaticRecoveryEnabled(true);

		for (int i = 0; i < consumerCount; i++) {
			int consumerId = i + 1;
			executorService.submit(() -> {
				try (Connection connection = factory.newConnection()) {
					MessageConsumer consumer = new MessageConsumer(queueName, connection, consumerId);
					consumer.start();
				} catch (Exception e) {
					logger.error("Consumer {} encountered an error", consumerId, e);
				}
			});
		}

		executorService.shutdown();
		executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
	}
}

class MessageConsumer {
	private static final Logger logger = LoggerFactory.getLogger(MessageConsumer.class);
	private static final int PREFETCH_COUNT = 10000;
	private final String queueName;
	private final Connection connection;
	private final int consumerId;

	public MessageConsumer(String queueName, Connection connection, int consumerId) {
		this.queueName = queueName;
		this.connection = connection;
		this.consumerId = consumerId;
	}

	public void start() throws Exception {
		try (Channel channel = connection.createChannel()) {
			channel.queueDeclare(queueName, true, false, false, null);
			channel.basicQos(PREFETCH_COUNT);

			logger.info("Consumer {} is ready to process messages from queue {}", consumerId, queueName);

			MessageHandler handler = new MessageHandler(channel, consumerId);
			DeliverCallback deliverCallback = (consumerTag, delivery) -> handler.processMessage(consumerTag, delivery);

			channel.basicConsume(queueName, false, deliverCallback, consumerTag -> {
				logger.info("Consumer {} cancelled", consumerId);
			});

			new CountDownLatch(1).await();
		}
	}
}

class MessageHandler {
	private static final Logger logger = LoggerFactory.getLogger(MessageHandler.class);
	private static final String KAFKA_TOPIC = "historical_data";
	private static final String KAFKA_BOOTSTRAP_SERVERS = "192.168.0.137:9092";

	private final Channel channel;
	private final int consumerId;
	private final KafkaProducer<String, String> kafkaProducer;
	private final ObjectMapper objectMapper = new ObjectMapper(); // Jackson ObjectMapper for JSON serialization

	public MessageHandler(Channel channel, int consumerId) {
		this.channel = channel;
		this.consumerId = consumerId;

		// Initialize Kafka producer
		Properties kafkaProperties = new Properties();
		kafkaProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVERS);
		kafkaProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		kafkaProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()); // Use// serializer
		this.kafkaProducer = new KafkaProducer<>(kafkaProperties);
	}

	public void processMessage(String consumerTag, Delivery delivery) {
		try {
			Universal universalMessage = Universal.parseFrom(delivery.getBody());

			for (ByteString serializedMessage : universalMessage.getMessagesList()) {
				processHistoricalMessage(serializedMessage);
			}

			// Acknowledge the message once it's processed
			channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
		} catch (InvalidProtocolBufferException e) {
			logger.error("Consumer {}: Failed to deserialize Universal message", consumerId, e);
			acknowledgeMessage(delivery);
		} catch (Exception e) {
			logger.error("Consumer {}: Error processing message", consumerId, e);
			acknowledgeMessage(delivery);
		}
	}

	private void acknowledgeMessage(Delivery delivery) {
		try {
			channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
		} catch (Exception nackEx) {
			logger.error("Consumer {}: Failed to nack message", consumerId, nackEx);
		}
	}

	private void processHistoricalMessage(ByteString serializedMessage) {
		try {
			Historical historicalMessage = Historical.parseFrom(serializedMessage);
			String sensor = historicalMessage.getSensor();

			for (HistoricalValue value : historicalMessage.getValuesList()) {
				long timestamp = value.getT();
				double sensorValue = value.getV();

				// Log the processed message
				logger.info("Processed historical message: Sensor={}, Timestamp={}, Value={}", sensor, timestamp,
						sensorValue);

				// Convert the data to JSON and send to Kafka
				sendToKafka(sensor, timestamp, sensorValue);
			}
		} catch (InvalidProtocolBufferException e) {
			logger.error("Consumer {}: Failed to deserialize Historical message", consumerId, e);
		}
	}

	private void sendToKafka(String sensor, long timestamp, double sensorValue) {
		try {
			// Create a HistoricalData object
			HistoricalData historicalData = new HistoricalData(sensor, timestamp, sensorValue);

			// Format the timestamp to ISO 8601 format
			String formattedTimestamp = formatTimestamp(timestamp);

			// Use the formatted timestamp in the JSON payload
			String jsonPayload = historicalData.toJsonString(formattedTimestamp);

			// Send the JSON payload to Kafka
			ProducerRecord<String, String> record = new ProducerRecord<>(KAFKA_TOPIC, sensor, jsonPayload);
			kafkaProducer.send(record, (metadata, exception) -> {
				if (exception != null) {
					logger.error("Consumer {}: Failed to send message to Kafka", consumerId, exception);
				} else {
					logger.info("Message sent to Kafka topic {}: partition {}, offset {}", metadata.topic(),
							metadata.partition(), metadata.offset());
				}
			});
		} catch (Exception e) {
			logger.error("Consumer {}: Error while sending message to Kafka", consumerId, e);
		}
	}

// Helper method to format timestamp
	private String formatTimestamp(long timestamp) {
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
		dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		return dateFormat.format(new Date(timestamp));
	}

// Inner class for representing the JSON payload
	private static class HistoricalData {
		private final String sensor;
		private final long timestamp;
		private final double value;

		public HistoricalData(String sensor, long timestamp, double value) {
			this.sensor = sensor;
			this.timestamp = timestamp;
			this.value = value;
		}

		// Getters are necessary for JSON serialization
		public String getSensor() {
			return sensor;
		}

		public long getTimestamp() {
			return timestamp;
		}

		public double getValue() {
			return value;
		}

		// Method to convert the object to a JSON string with custom date format
		public String toJsonString(String formattedTimestamp) throws Exception {
			ObjectMapper objectMapper = new ObjectMapper();

			// Disable timestamp serialization
			objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

			// Prepare a new instance with the formatted timestamp
			HistoricalDataWithFormattedTimestamp dataWithFormattedTimestamp = new HistoricalDataWithFormattedTimestamp(
					this.sensor, formattedTimestamp, this.value);

			return objectMapper.writeValueAsString(dataWithFormattedTimestamp);
		}

		// New class with formatted timestamp
		private static class HistoricalDataWithFormattedTimestamp {
			private final String sensor;
			private final String timestamp;
			private final double value;

			public HistoricalDataWithFormattedTimestamp(String sensor, String timestamp, double value) {
				this.sensor = sensor;
				this.timestamp = timestamp;
				this.value = value;
			}

			public String getSensor() {
				return sensor;
			}

			public String getTimestamp() {
				return timestamp;
			}

			public double getValue() {
				return value;
			}
		}
	}
}
