import java.text.SimpleDateFormat

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def AGENT = config.AGENT
    def CODEBASE_PATH = config.CODEBASE_PATH ?: ""
    def ENVIRONMENT = config.ENVIRONMENT ?: "dev"
    def git = new org.mahindra.Git()
    def pipelineStage = ""

    try {
        podTemplate(
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [
                containerTemplate(name: 'jnlp', image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:mss-cli')
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
                
                stage('Code Build') {
                    container('jnlp') {
                        pipelineStage = "${STAGE_NAME}"
                        echo "Building PHP application..."
                        
                        try {
                            sh """
                                echo "Current workspace: ${WORKSPACE}"
                                echo "CODEBASE_PATH: ${CODEBASE_PATH}"
                                echo "Listing workspace root:"
                                ls -lah ${WORKSPACE}
                                
                                # Navigate to the correct directory
                                cd ${WORKSPACE}${CODEBASE_PATH}
                                echo "Build completed successfully"
                                ls -lh
                            """
                            
                            echo "Build stage completed"
                            
                        } catch (Exception err) {
                            echo "Error during build: ${err.message}"
                            currentBuild.result = 'FAILURE'
                            throw err
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