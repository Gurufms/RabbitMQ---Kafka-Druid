#Author
@Anas
## Overview
This guide walks you through setting up a comprehensive data processing ecosystem integrating RabbitMQ, Kafka, Zookeeper, and Druid.

## 📋 Prerequisites
- Basic knowledge of distributed systems
- Java Runtime Environment (JRE)
- Python/Java development environment
- Administrative/root access

## 🛠 System Components
- **RabbitMQ**: Message broker
- **Kafka**: Distributed streaming platform
- **Zookeeper**: Coordination service
- **Druid**: Real-time analytics database

## 🔧 Step-by-Step Installation

### 1. RabbitMQ Setup
```bash
# Install RabbitMQ
$ sudo apt-get update
$ sudo apt-get install rabbitmq-server

# Start RabbitMQ Service
$ sudo systemctl start rabbitmq-server
$ sudo systemctl enable rabbitmq-server
```

### 2. Kafka & Zookeeper Installation
```bash
# Download Kafka
$ wget https://downloads.apache.org/kafka/latest/kafka_2.13-3.6.1.tgz
$ tar -xzf kafka_2.13-3.6.1.tgz
$ cd kafka_2.13-3.6.1

# Start Zookeeper (in one terminal)
$ bin/zookeeper-server-start.sh config/zookeeper.properties

# Start Kafka (in another terminal)
$ bin/kafka-server-start.sh config/server.properties
```

### 3. Druid Deployment
```bash
# Download Druid
$ wget https://downloads.apache.org/druid/latest/apache-druid-30.0.0-bin.tar.gz
$ tar -xzf apache-druid-30.0.0-bin.tar.gz
$ cd apache-druid-30.0.0

# Start Druid
$ bin/start-druid
```

## 🔌 Kafka Configuration
1. Create Kafka Topic
```bash
$ bin/kafka-topics.sh --create --topic historical_data \
    --bootstrap-server localhost:9092 --partitions 3 --replication-factor 1
```

## 🌐 Druid Web Console Setup
1. Open Druid Console: `http://localhost:8888`
2. Navigate to Kafka Ingestion
3. Configure Connection:
   - **Kafka Broker**: `localhost:9092`
   - **Topic**: `historical_data`

## 🚦 Application Startup Sequence
1. Start RabbitMQ
2. Launch Kafka & Zookeeper
3. Initialize Druid
4. Run Producer Application
5. Start Consumer Application

## 💡 Troubleshooting
- Ensure all services are running before starting applications
- Check port availability (default ports: 15672, 9092, 8888)
- Verify network connectivity
- Review log files for specific error details

## 🔒 Security Recommendations
- Configure authentication for RabbitMQ
- Use SSL for Kafka communications
- Enable Druid security features

## 📊 Monitoring
- RabbitMQ Management Plugin: `http://localhost:15672`
- Kafka Manager: Consider installing for cluster management
- Druid Metrics: Available in web console


