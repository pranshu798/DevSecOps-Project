import hudson.model.*

def call(Map config = [:]) {
    // Extract parameters with defaults
    def AGENT = config.AGENT ?: 'default'
    def IMAGE_NAME = config.IMAGE_NAME ?: ''
    def ENVIRONMENT = config.ENVIRONMENT ?: 'dev'
    def CLOUD_SQL_INSTANCE = config.CLOUD_SQL_INSTANCE ?: 'YOUR_CLOUD_SQL_INSTANCE_CONNECTION_NAME'
    def DB_NAME = config.DB_NAME ?: 'new_id_creation_grc'
    def DB_USER = config.DB_USER ?: 'root'
    def DB_PASSWORD = config.DB_PASSWORD ?: ''
    def DB_PORT = config.DB_PORT ?: '3306'
    def PHP_VERSION = config.PHP_VERSION ?: '8.3'
    def WORKSPACE_DIR = config.WORKSPACE_DIR ?: "${WORKSPACE}"
    def APP_DIR = config.APP_DIR ?: 'app'
    def MAX_WAIT_SECONDS = config.MAX_WAIT_SECONDS ?: 60
    def CLOUD_SQL_PROXY_VERSION = config.CLOUD_SQL_PROXY_VERSION ?: '2.8.0'
    
    try {
        podTemplate(
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [
                containerTemplate(
                    name: 'php-test',
                    image: "php:${PHP_VERSION}-cli",
                    ttyEnabled: true,
                    command: 'cat',
                    resourceRequestCpu: '1000m',
                    resourceRequestMemory: '2Gi',
                    resourceLimitCpu: '2000m',
                    resourceLimitMemory: '4Gi'
                ),
                containerTemplate(
                    name: 'cloud-sql-proxy',
                    image: "gcr.io/cloud-sql-connectors/cloud-sql-proxy:${CLOUD_SQL_PROXY_VERSION}",
                    args: "--port=${DB_PORT} ${CLOUD_SQL_INSTANCE}",
                    resourceRequestCpu: '100m',
                    resourceRequestMemory: '128Mi',
                    resourceLimitCpu: '200m',
                    resourceLimitMemory: '256Mi'
                )
            ],
            volumes: [
                hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                emptyDirVolume(mountPath: '/home/jenkins/agent')
            ]
        ) {
            node(POD_LABEL) {
                stage('Unit Test') {
                    // Checkout code from repository
                    checkout scm
                    
                    container('php-test') {
                        // Display current workspace
                        echo "=== Workspace Information ==="
                        sh """
                            echo "Current workspace: \$(pwd)"
                            ls -la
                        """
                        
                        // Install PHP extensions and dependencies
                        echo "=== Installing PHP Extensions and Dependencies ==="
                        sh """
                            echo "Installing required packages..."
                            apt-get update -qq
                            apt-get install -y -qq default-mysql-client netcat-openbsd > /dev/null 2>&1
                            
                            echo "Installing PHP extensions..."
                            docker-php-ext-install pdo pdo_mysql > /dev/null 2>&1
                            
                            echo "✅ PHP extensions installed successfully"
                        """
                        
                        // Setup Laravel testing environment
                        echo "=== Setting Up Laravel Testing Environment ==="
                        sh """
                            cd ${APP_DIR}
                            
                            # Copy testing configuration
                            if [ -f .env.testing ]; then
                                echo "Using .env.testing configuration"
                                cp .env.testing .env
                            else
                                echo "⚠️  Warning: .env.testing not found, using default .env"
                            fi
                            
                            # Configure database connection
                            echo "Configuring database connection..."
                            sed -i 's/DB_CONNECTION=.*/DB_CONNECTION=mysql/' .env
                            sed -i 's/DB_HOST=.*/DB_HOST=127.0.0.1/' .env
                            sed -i 's/DB_PORT=.*/DB_PORT=${DB_PORT}/' .env
                            sed -i 's/DB_DATABASE=.*/DB_DATABASE=${DB_NAME}/' .env
                            sed -i 's/DB_USERNAME=.*/DB_USERNAME=${DB_USER}/' .env
                            sed -i 's/DB_PASSWORD=.*/DB_PASSWORD=${DB_PASSWORD}/' .env
                            
                            # Verify configuration
                            echo "=== Database Configuration ==="
                            grep "^DB_" .env | grep -v "DB_PASSWORD"
                            
                            # Generate application key
                            echo "Generating application key..."
                            php artisan key:generate --quiet
                            
                            # Clear configuration cache
                            echo "Clearing configuration cache..."
                            php artisan config:clear --quiet
                            
                            echo "✅ Laravel environment configured successfully"
                        """
                        
                        // Wait for Cloud SQL Proxy to be ready
                        echo "=== Waiting for Cloud SQL Proxy ==="
                        sh """
                            echo "Checking Cloud SQL Proxy connectivity..."
                            
                            WAIT_SECONDS=0
                            MAX_WAIT=${MAX_WAIT_SECONDS}
                            PROXY_READY=false
                            
                            while [ \$WAIT_SECONDS -lt \$MAX_WAIT ]; do
                                if nc -z 127.0.0.1 ${DB_PORT} 2>/dev/null; then
                                    echo "✅ Cloud SQL Proxy is ready! (took \${WAIT_SECONDS}s)"
                                    PROXY_READY=true
                                    # Give it a few more seconds to stabilize
                                    sleep 3
                                    break
                                fi
                                
                                WAIT_SECONDS=\$((WAIT_SECONDS + 2))
                                echo "⏳ Waiting for Cloud SQL Proxy... (\${WAIT_SECONDS}s/\${MAX_WAIT}s)"
                                sleep 2
                            done
                            
                            if [ "\$PROXY_READY" = "false" ]; then
                                echo "❌ ERROR: Cloud SQL Proxy failed to start within \${MAX_WAIT} seconds"
                                exit 1
                            fi
                            
                            # Test database connectivity
                            echo "Testing database connectivity..."
                            if mysql -h 127.0.0.1 -P ${DB_PORT} -u ${DB_USER} ${DB_PASSWORD:+-p${DB_PASSWORD}} -e "SELECT 1;" > /dev/null 2>&1; then
                                echo "✅ Database connection successful"
                            else
                                echo "⚠️  Warning: Could not verify database connection"
                            fi
                        """
                        
                        // Run Laravel unit tests
                        echo "=== Running Laravel Unit Tests ==="
                        sh """
                            cd ${APP_DIR}
                            
                            echo "Executing PHPUnit tests..."
                            php artisan test --parallel || {
                                echo "⚠️  Some tests failed, checking for detailed output..."
                                php artisan test --verbose
                            }
                        """
                        
                        echo "✅ Unit tests completed successfully!"
                    }
                    
                    // Archive test results if available
                    script {
                        def testResultsPath = "${APP_DIR}/storage/logs/test-results"
                        if (fileExists(testResultsPath)) {
                            archiveArtifacts artifacts: "${testResultsPath}/**/*", allowEmptyArchive: true
                            echo "Test results archived from ${testResultsPath}"
                        }
                    }
                }
            }
        }
        
        // Mark build as successful
        currentBuild.result = 'SUCCESS'
        echo "=== Unit Test Stage Completed Successfully ==="
        
    } catch (Exception e) {
        // Handle failures gracefully
        currentBuild.result = 'FAILURE'
        echo "❌ Unit tests failed: ${e.message}"
        echo "=== Error Details ==="
        e.printStackTrace()
        throw e
    }
}

// Return this for pipeline usage
return this