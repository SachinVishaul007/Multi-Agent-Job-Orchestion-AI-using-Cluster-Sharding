# Distributed Architecture Implementation

## Overview
This project has been transformed from a sequential UI flow into a fully distributed system that properly utilizes Akka cluster sharding, scalability, fault tolerance, and event sourcing.

## Architecture Components

### 1. Distributed Controllers
- **ClusteredUploadController**: Handles file uploads with distributed processing and event sourcing
- **ClusteredMatchController**: Performs distributed job matching across multiple datasets with parallel processing
- **ClusteredEmployeeController**: Executes distributed employee search with circuit breaker patterns

### 2. Event Sourcing System
- **ResumeProcessorActor**: Event-sourced actor for processing resume data with complete audit trail
- **EventSourcingService**: Service layer that integrates event sourcing into the main workflow
- All resume processing events are persisted and recoverable

### 3. Cluster Sharding Strategy
- **Company-domain-based sharding**: Intelligent distribution based on company domains extracted from filenames and job descriptions
- **Smart fallback mechanisms**: Automatic fallback to local processing when cluster operations fail
- **Circuit breaker patterns**: Prevents cascade failures in distributed operations

### 4. Health Monitoring & Management
- **HealthController**: Comprehensive health checks for application, cluster, and services
- **ClusterManagementController**: Advanced cluster monitoring, testing, and management endpoints
- **Spring Boot Actuator**: Production-ready monitoring and metrics

## Running the Distributed System

### Single Node (Clustered Mode)
```bash
# Run with clustered profile (single node cluster)
mvn spring-boot:run -Dspring-boot.run.profiles=clustered
```

### Multi-Node Cluster
```bash
# Terminal 1 - Coordinator Node (seed node)
mvn spring-boot:run -Dspring-boot.run.profiles=node1

# Terminal 2 - Worker Node 2
mvn spring-boot:run -Dspring-boot.run.profiles=node2

# Terminal 3 - Worker Node 3
mvn spring-boot:run -Dspring-boot.run.profiles=node3

# Terminal 4 - Worker Node 4
mvn spring-boot:run -Dspring-boot.run.profiles=node4
```

### Access Points
- **Node 1 (Coordinator)**: http://localhost:8081
- **Node 2**: http://localhost:8082
- **Node 3**: http://localhost:8083
- **Node 4**: http://localhost:8084

## Key Features

### 1. Distributed Processing
- File uploads are distributed across cluster nodes with company-domain-based sharding
- Job matching searches across multiple datasets in parallel
- Employee searches utilize parallel page processing across cluster nodes

### 2. Fault Tolerance
- Circuit breaker patterns prevent cascade failures
- Automatic fallback to local processing when cluster operations fail
- Timeout handling with configurable timeouts for all distributed operations

### 3. Event Sourcing
- Complete audit trail of all resume processing operations
- Event replay capabilities for recovery and debugging
- Persistent actor state with LevelDB journal (production-ready)

### 4. Smart Sharding
- Company domain extraction from filenames and job descriptions
- Intelligent dataset distribution based on company affinity
- Balanced load distribution across cluster nodes

## Health Monitoring Endpoints

### Application Health
- `GET /health/status` - Overall application health
- `GET /health/readiness` - Kubernetes readiness probe
- `GET /health/liveness` - Kubernetes liveness probe
- `GET /health/metrics` - Basic application metrics

### Cluster Management
- `GET /api/cluster/info` - Detailed cluster information
- `GET /api/cluster/datasets` - Available datasets in cluster
- `GET /api/cluster/performance` - Performance metrics
- `POST /api/cluster/search/test` - Test distributed search functionality
- `POST /api/cluster/eventsourcing/test` - Test event sourcing system
- `POST /api/cluster/cleanup` - Trigger cleanup operations

### Spring Boot Actuator
- `GET /actuator/health` - Standard health endpoint
- `GET /actuator/metrics` - Detailed metrics
- `GET /actuator/info` - Application information

## Configuration Profiles

### application-clustered.properties
- Base cluster configuration with full feature set
- Production-ready persistence with LevelDB
- Circuit breaker and distributed data settings

### Node-Specific Profiles
- **node1**: Coordinator node with seed node role
- **node2-4**: Worker nodes for distributed processing
- Each node has isolated storage and logging

## API Changes

### Upload API (Distributed)
```
POST /api/upload
```
- Now includes event sourcing processing
- Distributed across cluster with company-domain sharding
- Returns event sourcing status in response

### Match API (Distributed) 
```
POST /api/match-experiences
```
- Searches across multiple datasets in parallel
- Includes distributed processing information
- Fallback to local processing with timeout handling

### Employee Search API (Distributed)
```
GET /api/search-employees
```
- Parallel page processing across cluster
- Circuit breaker protection
- Enhanced with predicted emails using distributed AI processing

## Monitoring & Debugging

### Log Patterns
- Each node logs with node identifier: `[NODE1]`, `[NODE2]`, etc.
- Cluster events and distributed operations are logged at INFO level
- Circuit breaker state changes are logged with warnings

### Troubleshooting
1. **Cluster not forming**: Check that all nodes can reach the seed node at `127.0.0.1:2551`
2. **Circuit breaker open**: Check error logs and wait for reset timeout (30 seconds)
3. **Event sourcing failures**: Check LevelDB journal directories and permissions
4. **Sharding issues**: Verify cluster roles are properly configured

## Performance Characteristics

### Throughput Improvements
- **File uploads**: Parallel processing across cluster nodes
- **Job matching**: Distributed search across multiple datasets
- **Employee search**: Parallel page fetching with up to 5 concurrent pages

### Fault Tolerance
- **Circuit breakers**: Prevent cascade failures with 3-failure threshold
- **Timeouts**: All operations have configurable timeouts (15-60 seconds)
- **Fallback processing**: Local processing when cluster is unavailable

### Scalability
- **Horizontal scaling**: Add more nodes by starting with different profiles
- **Load balancing**: Company-domain-based sharding distributes load intelligently
- **Resource isolation**: Each node maintains independent storage and processing

## Next Steps

1. **Production Deployment**: Configure external persistence (PostgreSQL + Cassandra)
2. **Load Balancer**: Add nginx/HAProxy for request distribution
3. **Monitoring**: Integrate with Prometheus/Grafana for advanced metrics
4. **Security**: Add authentication and authorization for cluster endpoints
5. **Kubernetes**: Deploy with Kubernetes for automatic scaling and management

This distributed architecture transforms the original "vibe-coded" sequential system into a production-ready, scalable, and fault-tolerant distributed application that properly utilizes all the sophisticated infrastructure that was originally implemented.