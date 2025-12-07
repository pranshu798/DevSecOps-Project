import java.text.SimpleDateFormat
import hudson.model.Result

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def SOURCE_REGISTRY = config.SOURCE_REGISTRY
    def TARGET_REGISTRY = config.TARGET_REGISTRY
    def IMAGE_NAME = config.IMAGE_NAME
    def SOURCE_PROJECT = config.SOURCE_PROJECT
    def TARGET_PROJECT = config.TARGET_PROJECT
    def SOURCE_ENV = config.SOURCE_ENV
    def TARGET_ENV = config.TARGET_ENV
    def SOURCE_IMAGE_TAG = config.SOURCE_IMAGE_TAG ?: "latest"
    def CLOUD = config.CLOUD
    def AGENT = config.AGENT
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
                        stage('Docker Image Promotion') {
                            pipelineStage = "${STAGE_NAME}"
                            
                            echo "=== Docker Image Promotion ==="
                            echo "Source: ${SOURCE_REGISTRY}/${IMAGE_NAME}:${SOURCE_IMAGE_TAG}"
                            echo "Target: ${TARGET_REGISTRY}/${IMAGE_NAME}"
                            echo "Environment: ${SOURCE_ENV} -> ${TARGET_ENV}"
                            
                            // Validate inputs
                            if (!SOURCE_REGISTRY || !TARGET_REGISTRY || !IMAGE_NAME) {
                                error("Missing required parameters for image promotion")
                            }
                            
                            // Ensure full registry paths
                            if (!SOURCE_REGISTRY.contains("asia-south1-docker.pkg.dev")) {
                                SOURCE_REGISTRY = "asia-south1-docker.pkg.dev/" + SOURCE_REGISTRY
                            }
                            if (!TARGET_REGISTRY.contains("asia-south1-docker.pkg.dev")) {
                                TARGET_REGISTRY = "asia-south1-docker.pkg.dev/" + TARGET_REGISTRY
                            }
                            
                            def sourceImage = "${SOURCE_REGISTRY}/${IMAGE_NAME}:${SOURCE_IMAGE_TAG}"
                            def verifiedTag = "${SOURCE_ENV}-verified"
                            def targetImageVerified = "${TARGET_REGISTRY}/${IMAGE_NAME}:${verifiedTag}"
                            def targetImageLatest = "${TARGET_REGISTRY}/${IMAGE_NAME}:latest"
                            def targetImageSourceTag = "${TARGET_REGISTRY}/${IMAGE_NAME}:${SOURCE_IMAGE_TAG}"
                            
                            // Use DD-MM-YYYY-HH-MM-SS format for timestamp
                            def timestamp = new Date().format('dd-MM-yyyy-HH-mm-ss')
                            def targetImageTimestamp = "${TARGET_REGISTRY}/${IMAGE_NAME}:${timestamp}"
                            
                            sh """
                                set -e  # Exit on any error
                                
                                whoami
                                gcloud --version
                                
                                echo "=========================================="
                                echo "STEP 1: AUTHENTICATE WITH SOURCE PROJECT"
                                echo "=========================================="
                                gcloud auth activate-service-account \
                                    --key-file=/home/jenkins/agent/key/key/mpaas-prod-all-access.json \
                                    --project=${SOURCE_PROJECT}
                                gcloud auth configure-docker asia-south1-docker.pkg.dev
                                
                                echo ""
                                echo "=========================================="
                                echo "STEP 2: PULL SOURCE IMAGE"
                                echo "=========================================="
                                echo "Pulling: ${sourceImage}"
                                docker pull ${sourceImage}
                                
                                # Capture source image digest for verification
                                SOURCE_DIGEST=\$(docker inspect --format='{{index .RepoDigests 0}}' ${sourceImage} | cut -d'@' -f2 || echo "")
                                if [ -z "\${SOURCE_DIGEST}" ]; then
                                    # Fallback: try to get digest from image ID
                                    SOURCE_DIGEST=\$(docker inspect --format='{{.Id}}' ${sourceImage})
                                fi
                                echo "Source Image Digest: \${SOURCE_DIGEST}"
                                echo "\${SOURCE_DIGEST}" > source_image_digest.txt
                                
                                echo ""
                                echo "=========================================="
                                echo "STEP 3: AUTHENTICATE WITH TARGET PROJECT"
                                echo "=========================================="
                                gcloud auth activate-service-account \
                                    --key-file=/home/jenkins/agent/key/key/mpaas-prod-all-access.json \
                                    --project=${TARGET_PROJECT}
                                gcloud auth configure-docker asia-south1-docker.pkg.dev
                                
                                echo ""
                                echo "=========================================="
                                echo "STEP 4: ENSURE TARGET REPOSITORY EXISTS"
                                echo "=========================================="
                                REPO_NAME=\$(echo "${TARGET_REGISTRY}" | awk -F'/' '{print \$NF}')
                                echo "Target repository: \${REPO_NAME}"
                                
                                if gcloud artifacts repositories describe \${REPO_NAME} \
                                    --location=asia-south1 \
                                    --project=${TARGET_PROJECT} 2>/dev/null; then
                                    echo "✓ Repository \${REPO_NAME} already exists"
                                else
                                    echo "Creating target repository \${REPO_NAME}..."
                                    gcloud artifacts repositories create \${REPO_NAME} \
                                        --repository-format=docker \
                                        --location=asia-south1 \
                                        --project=${TARGET_PROJECT} \
                                        --description="Docker repository for ${IMAGE_NAME} in ${TARGET_ENV}"
                                    echo "✓ Repository created successfully"
                                fi
                                
                                echo ""
                                echo "=========================================="
                                echo "STEP 5: TAG IMAGE WITH ALL REQUIRED TAGS"
                                echo "=========================================="
                                
                                # Tag 1: Environment verified tag
                                echo "Creating tag: ${verifiedTag}"
                                docker tag ${sourceImage} ${targetImageVerified}
                                
                                # Tag 2: Latest tag
                                echo "Creating tag: latest"
                                docker tag ${sourceImage} ${targetImageLatest}
                                
                                # Tag 3: Timestamp tag
                                echo "Creating tag: ${timestamp}"
                                docker tag ${sourceImage} ${targetImageTimestamp}
                                
                                # Tag 4: Source tag for traceability
                                echo "Creating tag: ${SOURCE_IMAGE_TAG} (source traceability)"
                                docker tag ${sourceImage} ${targetImageSourceTag}
                                
                                echo "✓ All tags created successfully"
                                
                                echo ""
                                echo "=========================================="
                                echo "STEP 6: PUSH ALL TAGS TO TARGET REGISTRY"
                                echo "=========================================="
                                
                                # Push verified tag
                                echo "Pushing: ${verifiedTag}"
                                docker push ${targetImageVerified}
                                echo "✓ Verified tag pushed"
                                
                                # Push latest tag
                                echo "Pushing: latest"
                                docker push ${targetImageLatest}
                                echo "✓ Latest tag pushed"
                                
                                # Push timestamp tag
                                echo "Pushing: ${timestamp}"
                                docker push ${targetImageTimestamp}
                                echo "✓ Timestamp tag pushed"
                                
                                # Push source tag
                                echo "Pushing: ${SOURCE_IMAGE_TAG}"
                                docker push ${targetImageSourceTag}
                                echo "✓ Source tag pushed"
                                
                                echo ""
                                echo "=========================================="
                                echo "STEP 7: VERIFY TAGS IN TARGET REGISTRY"
                                echo "=========================================="
                                
                                # List all tags for this image
                                echo "Listing all tags in target registry:"
                                gcloud artifacts docker tags list \
                                    ${TARGET_REGISTRY}/${IMAGE_NAME} \
                                    --project=${TARGET_PROJECT} \
                                    --format="table(tag,version,createTime,updateTime)" || echo "No tags found (this might be an error)"
                                
                                echo ""
                                echo "Verifying each pushed tag exists:"
                                
                                # Function to verify tag
                                verify_tag() {
                                    local tag=\$1
                                    local full_image="${TARGET_REGISTRY}/${IMAGE_NAME}:\${tag}"
                                    
                                    if gcloud artifacts docker images describe "\${full_image}" \
                                        --project=${TARGET_PROJECT} >/dev/null 2>&1; then
                                        
                                        TAG_DIGEST=\$(gcloud artifacts docker images describe "\${full_image}" \
                                            --project=${TARGET_PROJECT} \
                                            --format='get(image_summary.digest)' 2>/dev/null || echo "")
                                        
                                        echo "  ✓ Tag '\${tag}' exists - Digest: \${TAG_DIGEST}"
                                        
                                        # Verify digest matches source (if we have source digest)
                                        if [ -n "\${SOURCE_DIGEST}" ] && [ -n "\${TAG_DIGEST}" ]; then
                                            if [ "\${SOURCE_DIGEST}" = "\${TAG_DIGEST}" ]; then
                                                echo "    ✓ Digest matches source image"
                                            else
                                                echo "    ✗ WARNING: Digest does NOT match source image!"
                                                echo "      Source:  \${SOURCE_DIGEST}"
                                                echo "      Target:  \${TAG_DIGEST}"
                                            fi
                                        fi
                                    else
                                        echo "  ✗ CRITICAL ERROR: Tag '\${tag}' NOT FOUND in registry!"
                                        return 1
                                    fi
                                }
                                
                                # Verify all tags
                                verify_tag "${verifiedTag}"
                                verify_tag "latest"
                                verify_tag "${timestamp}"
                                verify_tag "${SOURCE_IMAGE_TAG}"
                                
                                echo ""
                                echo "=========================================="
                                echo "STEP 8: CLEANUP LOCAL IMAGES"
                                echo "=========================================="
                                
                                # Clean up local images (this only removes local copies, NOT from registry)
                                echo "Removing local Docker images to free up space..."
                                docker rmi ${sourceImage} 2>/dev/null || echo "  - Source image already removed"
                                docker rmi ${targetImageVerified} 2>/dev/null || echo "  - Verified tag already removed"
                                docker rmi ${targetImageLatest} 2>/dev/null || echo "  - Latest tag already removed"
                                docker rmi ${targetImageTimestamp} 2>/dev/null || echo "  - Timestamp tag already removed"
                                docker rmi ${targetImageSourceTag} 2>/dev/null || echo "  - Source tag already removed"
                                
                                echo "✓ Local cleanup completed"
                                echo ""
                                echo "=========================================="
                                echo "✓ IMAGE PROMOTION COMPLETED SUCCESSFULLY"
                                echo "=========================================="
                            """
                            
                            // Read the source digest for metadata
                            def sourceDigest = ""
                            try {
                                sourceDigest = readFile('source_image_digest.txt').trim()
                            } catch (Exception e) {
                                sourceDigest = "Unable to capture"
                            }
                            
                            // Create detailed promotion metadata
                            def promotionMetadata = """
╔════════════════════════════════════════════════════════════════════════════╗
║                     IMAGE PROMOTION SUMMARY                                ║
╚════════════════════════════════════════════════════════════════════════════╝

SOURCE INFORMATION:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Image:       ${sourceImage}
  Digest:      ${sourceDigest}
  Environment: ${SOURCE_ENV}
  Project:     ${SOURCE_PROJECT}

TARGET INFORMATION:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Registry:    ${TARGET_REGISTRY}
  Environment: ${TARGET_ENV}
  Project:     ${TARGET_PROJECT}

TAGS CREATED IN TARGET REGISTRY:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  ✓ ${verifiedTag}
    Purpose: Indicates this image was verified in ${SOURCE_ENV}
    Use Case: Promotion to next environment
    
  ✓ latest
    Purpose: Points to the current deployment-ready image
    Use Case: Kubernetes deployments using 'latest' tag
    
  ✓ ${timestamp}
    Purpose: Timestamped backup for audit and rollback
    Use Case: Rollback to specific promotion time
    
  ✓ ${SOURCE_IMAGE_TAG}
    Purpose: Original source tag for full traceability
    Use Case: Track back to source commit/build

PROMOTION DETAILS:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Promotion Flow:  ${SOURCE_ENV} → ${TARGET_ENV}
  Promotion Date:  ${new Date().format('dd/MM/yyyy HH:mm:ss')}
  Jenkins Build:   ${JOB_NAME} #${BUILD_NUMBER}
  Promoted By:     ${currentBuild.getBuildCauses()[0]?.userId ?: 'System'}
  Status:          ✓ SUCCESS

HOW TO USE THESE TAGS:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  1. To deploy in ${TARGET_ENV}:
     kubectl set image deployment/your-app container=${TARGET_REGISTRY}/${IMAGE_NAME}:latest
     
  2. To verify image source:
     gcloud artifacts docker images describe ${TARGET_REGISTRY}/${IMAGE_NAME}:${verifiedTag}
     
  3. To rollback to this specific promotion:
     kubectl set image deployment/your-app container=${TARGET_REGISTRY}/${IMAGE_NAME}:${timestamp}
     
  4. To trace back to source:
     gcloud artifacts docker images describe ${TARGET_REGISTRY}/${IMAGE_NAME}:${SOURCE_IMAGE_TAG}

VERIFICATION COMMANDS:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  # List all tags
  gcloud artifacts docker tags list ${TARGET_REGISTRY}/${IMAGE_NAME} --project=${TARGET_PROJECT}
  
  # Verify specific tag
  gcloud artifacts docker images describe ${TARGET_REGISTRY}/${IMAGE_NAME}:${verifiedTag} --project=${TARGET_PROJECT}

════════════════════════════════════════════════════════════════════════════════
"""
                            
                            writeFile file: 'image_promotion_metadata.txt', text: promotionMetadata
                            archiveArtifacts artifacts: 'image_promotion_metadata.txt,source_image_digest.txt', allowEmptyArchive: true
                            
                            // Set build description for easy identification in Jenkins UI
                            currentBuild.description = "✓ Promoted to ${TARGET_ENV} | Tags: ${verifiedTag}, latest, ${timestamp}"
                            
                            // Display the summary
                            echo promotionMetadata
                            
                            // Add a final confirmation
                            echo """
╔════════════════════════════════════════════════════════════════════════════╗
║  PROMOTION COMPLETE - All tags are now available in ${TARGET_ENV}           ║
║  Check Artifact Registry to verify tags are visible                       ║
╚════════════════════════════════════════════════════════════════════════════╝
"""
                        }
                    } else {
                        error("Image promotion currently only supports GCP (CLOUD=gcp)")
                    }
                }
            }
        }
    } catch (Exception err) {
        println err.getMessage()
        def failedStage = "${pipelineStage}"
        echo "❌ Image promotion failed at stage: ${pipelineStage}"
        echo "Error: ${err.getMessage()}"
        currentBuild.result = 'FAILURE'
        throw err
    }
}