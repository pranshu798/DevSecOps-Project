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
                        echo "GCP Project: ${GCP_PROJECT_ID}"
                        
                        stage('Docker Build & Push') {
                            pipelineStage = "${STAGE_NAME}"
                            
                            // Checkout source code
                            git.gitCheckout(5)
                            COMMIT_HASH = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                            echo "Commit Hash: ${COMMIT_HASH}"
                            echo "Status ${currentBuild.result}"
                            
                            // Authenticate with GCP and ensure repository exists
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
ENVIRONMENT=${env.BRANCH_NAME ?: 'unknown'}
REGISTRY=${REGISTRY_NAME}
"""
                            
                            writeFile file: 'image_metadata.txt', text: imageMetadata
                            archiveArtifacts artifacts: 'image_metadata.txt', allowEmptyArchive: true
                            
                            echo "Docker image built and pushed successfully"
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
                            stage('Docker Build & Push') {
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
ENVIRONMENT=${env.BRANCH_NAME ?: 'unknown'}
REGISTRY=${REGISTRY_NAME}
"""
                                
                                writeFile file: 'image_metadata.txt', text: imageMetadata
                                archiveArtifacts artifacts: 'image_metadata.txt', allowEmptyArchive: true
                                
                                echo "Docker image built and pushed successfully"
                                echo "Images: ${imageTag}, ${latestTag}"
                                currentBuild.description = "Image: ${shortCommitHash} (latest)"
                            }
                        }
                    }
                }
            }
        }
    } catch (Exception err) {
        println err.getMessage()
        def failedStage = "${pipelineStage}"
        echo "Build Failed at ${pipelineStage}"
        currentBuild.result = 'FAILURE'
        echo "Error caught"
        throw err
    }
}