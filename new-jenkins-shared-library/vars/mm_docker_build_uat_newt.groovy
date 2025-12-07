import java.text.SimpleDateFormat
import hudson.model.Actionable
import hudson.model.Result
def call(body)
{
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def REGISTRY_NAME           = config.REGISTRY_NAME
    def IMAGE_NAME              = config.IMAGE_NAME
    def AGENT                   = config.AGENT
    def CLOUD                   = config.CLOUD
    def DOCKERFILE_PATH         = config.DOCKERFILE_PATH
    def COMMIT_HASH             = ""
    def git = new org.mahindra.Git()

    try {
            podTemplate(
                nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
                containers: [containerTemplate(name: 'jnlp', image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:mss-cli-090823-1')],
                volumes: [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                         persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
                ]) {
                node(POD_LABEL) {
                currentBuild.result = 'SUCCESS'
                container('jnlp') {
                stage('Checkout source code') {

                        pipelineStage = "${STAGE_NAME}"
                        //step([$class: 'WsCleanup'])
                        git.gitCheckout(5)
                        COMMIT_HASH = sh(returnStdout: true, script: 'git rev-parse HEAD')
                        echo "Status ${currentBuild.result}"
                    }
                    withCredentials([
                        string(credentialsId: "modernization-common-application-id", variable: 'DOCKER_USERNAME'),
                        string(credentialsId: "modernization-common-password", variable: 'DOCKER_PASSWORD')]
                    ) {
                        if(CLOUD != null && CLOUD == "gcp") {
                            REGISTRY_NAME = "asia-south1-docker.pkg.dev/" + REGISTRY_NAME
                            stage('Docker Build & Push') {
                                if (DOCKERFILE_PATH != "" && DOCKERFILE_PATH != null) {
                                    sh """
                                        pwd
                                        ls
                                        docker build -t ${REGISTRY_NAME}/$IMAGE_NAME:${COMMIT_HASH.substring(0,9)} -f ${DOCKERFILE_PATH} .
                                    """
                                } else {
                                    sh """
                                        ls
                                        docker build -t ${REGISTRY_NAME}/$IMAGE_NAME:${COMMIT_HASH.substring(0,9)} .
                                     """
                                }
                                sh """
                                    whoami
                                    gcloud --version
                                    gcloud auth activate-service-account --key-file=/home/jenkins/agent/key/key/mpaas-prod-all-access.json --project=modernization-dev-832770
                                    gcloud auth configure-docker asia-south1-docker.pkg.dev
                                    docker push ${REGISTRY_NAME}/$IMAGE_NAME:${COMMIT_HASH.substring(0,9)}
                                """

                                sh """
                                           docker push ${REGISTRY_NAME}/$IMAGE_NAME:${COMMIT_HASH.substring(0,9)}
                                    """
                                echo "Docker image built and pushed successfully: ${REGISTRY_NAME}/${IMAGE_NAME}:${COMMIT_HASH.substring(0,9)}"
                            }
                        } else {
                            REGISTRY_NAME = REGISTRY_NAME + ".azurecr.io"
                            docker.image("docker:dind").inside("-u 1000:1000 --privileged -v /var/run/docker.sock:/var/run/docker.sock") {
                                stage('Build docker image') {
                                    sh """
                                        docker login ${REGISTRY_NAME} --username $DOCKER_USERNAME --password $DOCKER_PASSWORD
                                    """
                                    if (DOCKERFILE_PATH != "" && DOCKERFILE_PATH != null) {
                                        sh """
                                            docker build -t ${REGISTRY_NAME}/$IMAGE_NAME:${COMMIT_HASH.substring(0,9)} -f ${DOCKERFILE_PATH} .
                                        """
                                    } else {
                                        sh """
                                            docker build -t ${REGISTRY_NAME}/$IMAGE_NAME:${COMMIT_HASH.substring(0,9)} .
                                        """
                                    }
                                    sh """
                                           docker push ${REGISTRY_NAME}/$IMAGE_NAME:${COMMIT_HASH.substring(0,9)}
                                    """
                                    echo "Docker image built and pushed successfully: ${REGISTRY_NAME}/${IMAGE_NAME}:${COMMIT_HASH.substring(0,9)}"
                                }
                            }
                        }
                    }
                }

            }
        }

    } catch (Exception e) {
        echo "Error in Docker Build stage: ${e.message}"
        currentBuild.result = 'FAILURE'
        failedStage = "${pipelineStage}"
        echo "Build Failed at ${pipelineStage}"
    }
}