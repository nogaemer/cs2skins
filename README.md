# CS2 Skins Trade-Up Calculator - Spring Boot API

## Overview
This application calculates profitable CS2 (Counter-Strike 2) skin trade-up opportunities and exposes them via a REST API. It has been transformed from a console application to a production-ready Spring Boot backend.

## Architecture

### Technology Stack
- **Spring Boot 3.2.1** - Main application framework
- **Kotlin 2.2.0** - Programming language
- **JetBrains Exposed 0.55.0** - SQL framework
- **MySQL** - Database
- **HikariCP** - Connection pooling (via Spring Boot)
- **Jackson** - JSON serialization

### Project Structure
```
src/main/kotlin/
├── Main.kt                              # Legacy console entry point
├── com/nogaemer/cs2skins/
│   ├── Application.kt                   # Spring Boot application entry point
│   ├── controller/                      # REST API controllers
│   │   ├── CollectionController.kt      # Collection endpoints
│   │   ├── SkinController.kt            # Skin endpoints  
│   │   ├── TradeUpController.kt         # Trade-up endpoints
│   │   └── SystemController.kt          # System/admin endpoints
│   ├── service/                         # Business logic services
│   │   ├── CollectionService.kt
│   │   ├── SkinService.kt
│   │   ├── TradeUpService.kt
│   │   └── SeedService.kt
│   └── dto/                             # Data Transfer Objects
│       └── ApiDtos.kt
├── database/                            # Database layer (Exposed)
│   ├── Tables.kt                        # Database schema
│   ├── *Repository.kt                   # Data access layer
│   ├── DatabaseFactory.kt               # Legacy DB initialization
│   └── SeedDB.kt                        # Data seeding utility
├── models/                              # Domain models
└── tradeup/                             # Core calculation logic
    └── TradeUpOptimizer.kt              # Main algorithm (unchanged)
```

## Getting Started

### Prerequisites
- Java 17 or higher
- Maven 3.6+
- MySQL 8.0+

### Database Configuration
Update `src/main/resources/application.properties` with your MySQL credentials:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/skins_schema?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
spring.datasource.username=YOUR_USERNAME
spring.datasource.password=YOUR_PASSWORD
```

### Building the Application
```bash
mvn clean package
```

### Running the Application

**Option 1: Using Maven**
```bash
mvn spring-boot:run
```

**Option 2: Using JAR**
```bash
java -jar target/cs2skins_v2-1.0-SNAPSHOT.jar
```

The API will be available at `http://localhost:8080`

## API Documentation

Comprehensive API documentation is available in [API_DOCUMENTATION.md](./API_DOCUMENTATION.md).

### Quick Start API Workflow

1. **Initialize Data** (First-time setup)
```bash
# Seed collections from external API
curl -X POST http://localhost:8080/api/system/seed/collections

# Seed skins and prices (this may take several minutes)
curl -X POST http://localhost:8080/api/system/seed/skins

# Calculate trade-ups (computationally intensive)
curl -X POST http://localhost:8080/api/system/calculate/all
```

2. **Query Results**
```bash
# Get all trade-ups
curl http://localhost:8080/api/tradeups

# Get filtered trade-ups
curl -X POST http://localhost:8080/api/tradeups/filter \
  -H "Content-Type: application/json" \
  -d '{"sortBy":"profit","sortDirection":"desc","minRoi":1.2}'

# Search for specific skins
curl -X POST http://localhost:8080/api/skins/search \
  -H "Content-Type: application/json" \
  -d '{"weaponId":"weapon-ak47"}'

# Get all collections
curl http://localhost:8080/api/collections
```

## Key Features

### 1. Skins Management
- Search and filter skins by weapon, rarity, collection
- View detailed skin information including prices by wear condition
- Support for both regular and StatTrak™ skins

### 2. Collections
- List all CS2 skin collections
- View collection details with all contained skins
- Support for browsing by rarity tiers

### 3. Trade-Up Calculations
- Automated calculation of profitable trade-up opportunities
- Filtering and sorting by ROI, profit, input cost
- Persistent storage of calculated results
- Support for both regular and StatTrak™ trade-ups

### 4. System Administration
- Asynchronous job execution for data seeding
- Background calculation jobs
- Job status monitoring
- Database management

## Core Logic Preservation

The core trade-up calculation logic in `TradeUpOptimizer.kt` **remains unchanged** from the original console application. The Spring Boot transformation:
- ✅ Wraps existing logic in service beans
- ✅ Persists calculation results to database
- ✅ Exposes functionality via REST endpoints
- ❌ Does NOT modify the mathematical algorithms

## Database Schema

The application automatically creates the following tables on startup:

### Original Tables
- `collections` - CS2 skin collections
- `weapons` - Weapon types
- `rarities` - Rarity tiers (Consumer, Industrial, Mil-Spec, etc.)
- `wear_conditions` - Wear types (Factory New, Minimal Wear, etc.)
- `skins` - Individual skins with metadata
- `skin_prices` - Price information by wear condition

### New Tables (Spring Boot)
- `tradeup_results` - Calculated trade-up opportunities
- `tradeup_inputs` - Input skins for each trade-up
- `tradeup_outputs` - Possible output skins with probabilities

## Development

### Running Tests
```bash
mvn test
```

### Building without Tests
```bash
mvn clean package -DskipTests
```

### Code Structure Guidelines
- Controllers handle HTTP requests/responses
- Services contain business logic
- Repositories manage data access
- DTOs define API contracts
- Models represent domain entities

## Migration Notes

### What Changed
1. **Entry Point**: `Main.kt` → `Application.kt`
2. **Database Init**: Manual `DatabaseFactory` → Spring-managed DataSource
3. **Execution**: Synchronous console output → Asynchronous REST endpoints
4. **Data Storage**: Print to console → Persist to database

### What Didn't Change
- Core calculation algorithms in `TradeUpOptimizer.kt`
- Database schema for existing tables
- Data models and entities
- JetBrains Exposed SQL layer

### Backward Compatibility
The legacy console application (`Main.kt`) is still available but is not the recommended entry point. To run it:
```bash
mvn exec:java -Dexec.mainClass="MainKt"
```

## Production Considerations

### Before Deploying
1. **Security**: Add authentication/authorization (Spring Security)
2. **Configuration**: Externalize database credentials (environment variables)
3. **Monitoring**: Add actuator endpoints for health checks
4. **Logging**: Configure appropriate log levels
5. **Performance**: Tune HikariCP connection pool settings
6. **Caching**: Consider caching for frequently accessed data
7. **Rate Limiting**: Add rate limiting for expensive operations

### Recommended Enhancements
- Add API versioning (`/api/v1/...`)
- Implement pagination for large result sets
- Add WebSocket support for real-time updates
- Configure CORS for frontend integration
- Add API documentation UI (Swagger/OpenAPI)

## Troubleshooting

### Application Won't Start
- Check MySQL is running and accessible
- Verify database credentials in `application.properties`
- Ensure port 8080 is not already in use

### Calculation Jobs Hang
- These are CPU-intensive operations that can take 10+ minutes
- Check `/api/system/status` to monitor job progress
- Ensure sufficient heap memory (add `-Xmx2g` to JVM args if needed)

### API Returns Empty Results
- Ensure data has been seeded (`/api/system/seed/all`)
- Verify calculations have been run (`/api/system/calculate/all`)
- Check database has been populated correctly

## License

[Add your license information here]

## Contributing

[Add contribution guidelines here]

## Contact

[Add contact information here]
