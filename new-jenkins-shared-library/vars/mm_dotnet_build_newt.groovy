import java.text.SimpleDateFormat

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def AGENT = config.AGENT
    def CODEBASE_PATH = config.CODEBASE_PATH ?: ''
    def DOTNET_IMAGE = config.DOTNET_IMAGE ?: 'mcr.microsoft.com/dotnet/sdk:8.0'
    def PROJECT = config.PROJECT
    def IMAGE_NAME = config.IMAGE_NAME
    def FRAMEWORK = config.FRAMEWORK ?: "DOTNET_CORE"
    def BUILD_CONFIGURATION = config.BUILD_CONFIGURATION ?: "Release"
    def git = new org.mahindra.Git()
    def pipelineStage = ""

    // Validate required parameters
    if (!PROJECT || PROJECT.trim().isEmpty()) {
        error("PROJECT parameter is required and cannot be empty")
    }
    if (!IMAGE_NAME || IMAGE_NAME.trim().isEmpty()) {
        error("IMAGE_NAME parameter is required and cannot be empty")
    }

    echo "=== .NET Build Configuration ==="
    echo "Project: ${PROJECT}"
    echo "Image Name: ${IMAGE_NAME}"
    echo "Codebase Path: ${CODEBASE_PATH}"
    echo "Build Configuration: ${BUILD_CONFIGURATION}"
    echo "==============================="

    try {
        podTemplate(
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [
                containerTemplate(name: 'jnlp', image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:mss-cli', nodeSelector: 'node: "primary"')
            ],
            volumes: [
                hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
            ]
        ) {
            node(POD_LABEL) {
                currentBuild.result = 'SUCCESS'
                
                stage('Checkout source code') {
                    container('jnlp') {
                        pipelineStage = "${STAGE_NAME}"
                        git.gitCheckout(5)
                        echo "Status ${currentBuild.result}"
                    }
                }
                
                stage('Code build') {
                    container('jnlp') {
                        pipelineStage = "${STAGE_NAME}"
                        
                        // Install .NET SDK
                        sh """
                            if [ -f /etc/apt/sources.list.d/kubernetes.list ]; then
                                sed -i '/kubernetes/d' /etc/apt/sources.list.d/kubernetes.list
                            fi
                            apt-get update -y
                            
                            echo "Checking installed .NET SDKs..."
                            dotnet --list-sdks || true

                            echo "Clearing old NuGet caches..."
                            dotnet nuget locals all --clear || true

                            echo "Removing older .NET SDK versions..."
                            rm -rf /usr/share/dotnet/sdk/6.* || true
                            rm -rf /usr/share/dotnet/shared/Microsoft.NETCore.App/6.* || true

                            echo "Installing .NET SDK 8.0..."
                            wget https://dotnet.microsoft.com/download/dotnet/scripts/v1/dotnet-install.sh -O dotnet-install.sh
                            chmod +x dotnet-install.sh
                            ./dotnet-install.sh --channel 8.0 --install-dir /usr/share/dotnet

                            export PATH=/usr/share/dotnet:\$PATH

                            echo "Verifying .NET SDK installation..."
                            dotnet --list-sdks
                        """
                        
                        // Set working directory
                        def WORK_DIR = CODEBASE_PATH ? "${WORKSPACE}${CODEBASE_PATH}" : "${WORKSPACE}"
                        
                        echo "Working directory: ${WORK_DIR}"
                        
                        // Restore .NET dependencies
                        sh """
                            cd "${WORK_DIR}"
                            echo "Current directory: \$(pwd)"
                            echo "Directory contents:"
                            ls -lh
                            
                            echo "Restoring .NET dependencies..."
                            dotnet restore
                        """
                        
                        // Build .NET application
                        sh """
                            cd "${WORK_DIR}"
                            echo "Building .NET application..."
                            dotnet build --configuration ${BUILD_CONFIGURATION} --no-restore
                        """
                        
                        // Publish .NET application
                        sh """
                            cd "${WORK_DIR}"
                            echo "Publishing .NET application..."
                            dotnet publish --configuration ${BUILD_CONFIGURATION} --no-build --output ${WORKSPACE}/publish
                            
                            echo "Build output:"
                            ls -la ${WORKSPACE}/publish/
                        """
                        
                        // Stash build output for Docker stage (don't archive to reduce clutter)
                        stash includes: 'publish/**', name: 'dotnet-build-output'
                        
                        echo ".NET build completed successfully for ${PROJECT}"
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