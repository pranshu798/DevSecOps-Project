import java.text.SimpleDateFormat
import hudson.model.Actionable
import hudson.model.Result

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def REGISTRY_NAME = config.REGISTRY_NAME
    def IMAGE_NAME = config.IMAGE_NAME
    def AGENT = config.AGENT
    def CLOUD = config.CLOUD
    def DOCKERFILE_PATH = config.DOCKERFILE_PATH
    def GCP_PROJECT_ID = config.GCP_PROJECT_ID ?: "modernization-dev-832770"
    def COMMIT_HASH = ""
    def git = new org.mahindra.Git()
    def pipelineStage = ""

    try {
        podTemplate(
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [containerTemplate(name: 'jnlp', image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:mss-cli-090823-1')],
            volumes: [
                hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
            ]
        ) {
            node(POD_LABEL) {
                currentBuild.result = 'SUCCESS'
                container('jnlp') {
                    withCredentials([
                        string(credentialsId: "PrismaUsername1", variable: 'PC_User'),
                        string(credentialsId: "PrismaPassword1", variable: 'PC_Password')
                    ]) {
                        if (CLOUD != null && CLOUD == "gcp") {
                            // Validate REGISTRY_NAME format for GCP
                            if (!REGISTRY_NAME || REGISTRY_NAME.trim().isEmpty()) {
                                error("REGISTRY_NAME is required and cannot be empty. Expected format: 'project-id/repository-name'")
                            }
                            
                            // Check if REGISTRY_NAME already contains the full path
                            if (!REGISTRY_NAME.contains("asia-south1-docker.pkg.dev")) {
                                REGISTRY_NAME = "asia-south1-docker.pkg.dev/" + REGISTRY_NAME
                            }
                            
                            echo "Using GCP Registry: ${REGISTRY_NAME}"
                            echo "Image Name: ${IMAGE_NAME}"
                            
                            stage('Docker Build & Image Scan: Prisma') {
                                pipelineStage = "${STAGE_NAME}"
                                
                                // Checkout source code
                                git.gitCheckout(5)
                                COMMIT_HASH = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                                echo "Commit Hash: ${COMMIT_HASH}"
                                echo "Status ${currentBuild.result}"
                                
                                // Authenticate with GCP
                                sh """
                                    whoami
                                    gcloud --version
                                    gcloud auth activate-service-account --key-file=/home/jenkins/agent/key/key/mpaas-prod-all-access.json --project=${GCP_PROJECT_ID}
                                    
                                    # Extract repository name from REGISTRY_NAME
                                    REPO_NAME=\$(echo "${REGISTRY_NAME}" | awk -F'/' '{print \$NF}')
                                    echo "Repository name: \${REPO_NAME}"
                                    
                                    # Check if repository exists, create if not
                                    if ! gcloud artifacts repositories describe \${REPO_NAME} --location=asia-south1 --project=${GCP_PROJECT_ID} 2>/dev/null; then
                                        echo "Repository \${REPO_NAME} not found. Creating..."
                                        gcloud artifacts repositories create \${REPO_NAME} \
                                            --repository-format=docker \
                                            --location=asia-south1 \
                                            --project=${GCP_PROJECT_ID} \
                                            --description="Docker repository for ${IMAGE_NAME}"
                                        echo "Repository created successfully"
                                    else
                                        echo "Repository \${REPO_NAME} already exists"
                                    fi
                                    
                                    gcloud auth configure-docker asia-south1-docker.pkg.dev
                                """
                                
                                // Build Docker image
                                def shortCommitHash = COMMIT_HASH.substring(0, 9)
                                def imageTag = "${REGISTRY_NAME}/${IMAGE_NAME}:${shortCommitHash}"
                                def latestTag = "${REGISTRY_NAME}/${IMAGE_NAME}:latest"
                                
                                echo "Building image: ${imageTag}"
                                
                                if (DOCKERFILE_PATH != "" && DOCKERFILE_PATH != null) {
                                    sh """
                                        pwd
                                        ls
                                        docker build -t ${imageTag} -f ${DOCKERFILE_PATH} .
                                    """
                                } else {
                                    sh """
                                        pwd
                                        ls
                                        docker build -t ${imageTag} .
                                    """
                                }
                                
                                // Download twistcli and run Prisma scan
                                sh """
                                    token=\$(curl -k -H "Content-Type: application/json" -X POST -d '{"username":"${PC_User}","password":"${PC_Password}"}' https://asia-south1.cloud.twistlock.com/india-1131963681/api/v1/authenticate | grep -o '"token":"[^"]*' | grep -o '[^"]*\$')
                                    curl --progress-bar -L --header "authorization: Bearer \$token" https://asia-south1.cloud.twistlock.com/india-1131963681/api/v1/util/twistcli > twistcli
                                    chmod a+x twistcli
                                """
                                
                                sh "./twistcli images scan -u ${PC_User} -p ${PC_Password} --address https://asia-south1.cloud.twistlock.com/india-1131963681 --details --ci --output-file image_scan_results.json ${imageTag}"
                                
                                // Rename JSON file
                                sh "mv image_scan_results.json prisma_report.json"
                                
                                // Archive renamed report
                                archiveArtifacts 'prisma_report.json'

                                // Generate vulnerability count report
                                generateVulnerabilityReport(imageTag)

                                // Stash reports
                                stash includes: 'prisma_report.json,vulnerability_count_prisma.txt', name: 'prisma-reports'

                                // Tag with latest
                                echo "Tagging image as latest: ${latestTag}"
                                sh """
                                    docker tag ${imageTag} ${latestTag}
                                """

                                // Push both tags to registry
                                echo "Pushing image with commit hash: ${imageTag}"
                                sh """
                                    docker push ${imageTag}
                                """
                                
                                echo "Pushing image with latest tag: ${latestTag}"
                                sh """
                                    docker push ${latestTag}
                                """
                                
                                // Save image metadata for promotion
                                def imageMetadata = """
IMAGE_TAG=${shortCommitHash}
FULL_IMAGE=${imageTag}
LATEST_IMAGE=${latestTag}
COMMIT_HASH=${COMMIT_HASH}
BUILD_NUMBER=${BUILD_NUMBER}
BUILD_TIMESTAMP=${new Date().format('yyyy-MM-dd HH:mm:ss')}
ENVIRONMENT=gcp_dev
REGISTRY=${REGISTRY_NAME}
"""
                                
                                writeFile file: 'image_metadata.txt', text: imageMetadata
                                archiveArtifacts artifacts: 'image_metadata.txt', allowEmptyArchive: true
                                
                                echo "Docker image built, scanned, and pushed successfully"
                                echo "Images: ${imageTag}, ${latestTag}"
                                currentBuild.description = "Image: ${shortCommitHash} (latest)"
                            }
                        } else {
                            // Azure path
                            if (!REGISTRY_NAME || REGISTRY_NAME.trim().isEmpty()) {
                                error("REGISTRY_NAME is required and cannot be empty")
                            }
                            
                            if (!REGISTRY_NAME.contains(".azurecr.io")) {
                                REGISTRY_NAME = REGISTRY_NAME + ".azurecr.io"
                            }
                            
                            echo "Using Azure Registry: ${REGISTRY_NAME}"
                            echo "Image Name: ${IMAGE_NAME}"
                            
                            docker.image("docker:dind").inside("-u 1000:1000 --privileged -v /var/run/docker.sock:/var/run/docker.sock") {
                                stage('Docker Build & Image Scan: Prisma') {
                                    pipelineStage = "${STAGE_NAME}"
                                    
                                    // Checkout source code
                                    git.gitCheckout(5)
                                    COMMIT_HASH = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                                    echo "Commit Hash: ${COMMIT_HASH}"
                                    echo "Status ${currentBuild.result}"
                                    
                                    // Docker login
                                    withCredentials([
                                        string(credentialsId: 'AZURE_DOCKER_USERNAME', variable: 'DOCKER_USERNAME'),
                                        string(credentialsId: 'AZURE_DOCKER_PASSWORD', variable: 'DOCKER_PASSWORD')
                                    ]) {
                                        sh """
                                            docker login ${REGISTRY_NAME} --username $DOCKER_USERNAME --password $DOCKER_PASSWORD
                                        """
                                    }
                                    
                                    // Build Docker image
                                    def shortCommitHash = COMMIT_HASH.substring(0, 9)
                                    def imageTag = "${REGISTRY_NAME}/${IMAGE_NAME}:${shortCommitHash}"
                                    def latestTag = "${REGISTRY_NAME}/${IMAGE_NAME}:latest"
                                    
                                    echo "Building image: ${imageTag}"
                                    
                                    if (DOCKERFILE_PATH != "" && DOCKERFILE_PATH != null) {
                                        sh """
                                            docker build -t ${imageTag} -f ${DOCKERFILE_PATH} .
                                        """
                                    } else {
                                        sh """
                                            docker build -t ${imageTag} .
                                        """
                                    }
                                    
                                    // Download twistcli and run scan
                                    sh """
                                        token=\$(curl -k -H "Content-Type: application/json" -X POST -d '{"username":"${PC_User}","password":"${PC_Password}"}' https://asia-south1.cloud.twistlock.com/india-1131963681/api/v1/authenticate | grep -o '"token":"[^"]*' | grep -o '[^"]*\$')
                                        curl --progress-bar -L --header "authorization: Bearer \$token" https://asia-south1.cloud.twistlock.com/india-1131963681/api/v1/util/twistcli > twistcli
                                        chmod a+x twistcli
                                    """
                                    
                                    sh "./twistcli images scan -u ${PC_User} -p ${PC_Password} --address https://asia-south1.cloud.twistlock.com/india-1131963681 --details --ci --output-file image_scan_results.json ${imageTag}"
                                    
                                    // Rename JSON file
                                    sh "mv image_scan_results.json prisma_report.json"
                                    
                                    // Archive renamed report
                                    archiveArtifacts 'prisma_report.json'
                                    
                                    // Generate vulnerability count report
                                    generateVulnerabilityReport(imageTag)

                                    // Tag with latest
                                    echo "Tagging image as latest: ${latestTag}"
                                    sh """
                                        docker tag ${imageTag} ${latestTag}
                                    """

                                    // Push both tags to registry
                                    echo "Pushing image with commit hash: ${imageTag}"
                                    sh """
                                        docker push ${imageTag}
                                    """
                                    
                                    echo "Pushing image with latest tag: ${latestTag}"
                                    sh """
                                        docker push ${latestTag}
                                    """

                                    // Save image metadata for promotion
                                    def imageMetadata = """
IMAGE_TAG=${shortCommitHash}
FULL_IMAGE=${imageTag}
LATEST_IMAGE=${latestTag}
COMMIT_HASH=${COMMIT_HASH}
BUILD_NUMBER=${BUILD_NUMBER}
BUILD_TIMESTAMP=${new Date().format('yyyy-MM-dd HH:mm:ss')}
ENVIRONMENT=gcp_dev
REGISTRY=${REGISTRY_NAME}
"""
                                    
                                    writeFile file: 'image_metadata.txt', text: imageMetadata
                                    archiveArtifacts artifacts: 'image_metadata.txt', allowEmptyArchive: true

                                    echo "Docker image built, scanned, and pushed successfully"
                                    echo "Images: ${imageTag}, ${latestTag}"
                                    currentBuild.description = "Image: ${shortCommitHash} (latest)"

                                    // Stash reports
                                    stash includes: 'prisma_report.json,vulnerability_count_prisma.txt', name: 'prisma-reports'
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (Exception e) {
        echo "Error in Prisma Scan stage: ${e.message}"
        e.printStackTrace()
        currentBuild.result = 'UNSTABLE'
        throw e
    }
}

// Reusable function to generate vulnerability report
def generateVulnerabilityReport(imageName) {
    try {
        sh '''
            #!/bin/bash
            echo "=== PRISMA VULNERABILITY SCAN RESULTS ==="
            echo "Report file: ${WORKSPACE}/prisma_report.json"
            
            # Check if file exists
            if [ ! -f "${WORKSPACE}/prisma_report.json" ]; then
                echo "Error: Results file not found!"
                exit 1
            fi
            
            echo "Parsing JSON results..."
            
            # Extract vulnerability counts with proper error handling
            CRITICAL=$(grep -A 10 '"vulnerabilityDistribution"' "${WORKSPACE}/prisma_report.json" | grep '"critical"' | grep -o '[0-9]\\+' | head -1 || echo "0")
            HIGH=$(grep -A 10 '"vulnerabilityDistribution"' "${WORKSPACE}/prisma_report.json" | grep '"high"' | grep -o '[0-9]\\+' | head -1 || echo "0")
            MEDIUM=$(grep -A 10 '"vulnerabilityDistribution"' "${WORKSPACE}/prisma_report.json" | grep '"medium"' | grep -o '[0-9]\\+' | head -1 || echo "0")
            LOW=$(grep -A 10 '"vulnerabilityDistribution"' "${WORKSPACE}/prisma_report.json" | grep '"low"' | grep -o '[0-9]\\+' | head -1 || echo "0")
            TOTAL=$(grep -A 10 '"vulnerabilityDistribution"' "${WORKSPACE}/prisma_report.json" | grep '"total"' | grep -o '[0-9]\\+' | head -1 || echo "0")
            
            # Set defaults if empty
            CRITICAL=${CRITICAL:-0}
            HIGH=${HIGH:-0}
            MEDIUM=${MEDIUM:-0}
            LOW=${LOW:-0}
            TOTAL=${TOTAL:-0}
            
            echo "Extracted vulnerability counts:"
            echo "Critical: ${CRITICAL}"
            echo "High: ${HIGH}"
            echo "Medium: ${MEDIUM}"
            echo "Low: ${LOW}"
            echo "Total: ${TOTAL}"
            
            # Create vulnerability_count_prisma.txt
            cat > "${WORKSPACE}/vulnerability_count_prisma.txt" << EOF
PRISMA VULNERABILITY SCAN SUMMARY
Image: ''' + imageName + '''
Scan Date: $(date)
Build: ${JOB_NAME} #${BUILD_NUMBER}

VULNERABILITY COUNTS:
Critical: ${CRITICAL}
High: ${HIGH}
Medium: ${MEDIUM}
Low: ${LOW}
Total: ${TOTAL}

STATUS: $(if [ "${CRITICAL}" -gt 0 ] || [ "${HIGH}" -gt 0 ]; then echo "ATTENTION REQUIRED"; else echo "ACCEPTABLE"; fi)
EOF
            
            echo "Vulnerability report generated successfully"
        '''
        
        // Parse results and set build status using Jenkins native JSON parsing
        try {
            def prismaScanResult = readJSON file: "${WORKSPACE}/prisma_report.json"
            def reportMap = [critical: 0, high: 0, medium: 0, low: 0, total: 0]
            
            if (prismaScanResult.results && prismaScanResult.results[0] && prismaScanResult.results[0].vulnerabilityDistribution) {
                def vulnerabilityDistribution = prismaScanResult.results[0].vulnerabilityDistribution
                reportMap = [
                    critical: vulnerabilityDistribution.critical ?: 0,
                    high: vulnerabilityDistribution.high ?: 0,
                    medium: vulnerabilityDistribution.medium ?: 0,
                    low: vulnerabilityDistribution.low ?: 0,
                    total: vulnerabilityDistribution.total ?: 0
                ]
            }
            
            echo "Prisma scan completed. Critical: ${reportMap.critical}, High: ${reportMap.high}, Medium: ${reportMap.medium}, Low: ${reportMap.low}, Total: ${reportMap.total}"
            
            // Archive the vulnerability report
            archiveArtifacts artifacts: 'vulnerability_count_prisma.txt', allowEmptyArchive: true
            
            // Set build status based on vulnerability counts
            if (reportMap.critical > 0) {
                currentBuild.result = 'UNSTABLE'
                echo "Critical vulnerabilities found (${reportMap.critical}) - marking build as UNSTABLE"
            } else if (reportMap.high > 10) {
                currentBuild.result = 'UNSTABLE'
                echo "High number of high-severity vulnerabilities (${reportMap.high}) - marking build as UNSTABLE"
            } else {
                echo "Vulnerability scan passed - no critical vulnerabilities found"
            }
            
        } catch (Exception jsonException) {
            echo "Warning: Could not parse JSON results for detailed analysis: ${jsonException.message}"
            // Fallback to basic shell-based analysis
            def vulnerabilityReport = readFile "${WORKSPACE}/vulnerability_count_prisma.txt"
            echo "Vulnerability report content:\n${vulnerabilityReport}"
        }
        
    } catch (Exception e) {
        echo "Error generating vulnerability report: ${e.message}"
        currentBuild.result = 'UNSTABLE'
    }
}