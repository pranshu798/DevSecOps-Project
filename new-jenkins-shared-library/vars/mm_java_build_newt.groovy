import java.text.SimpleDateFormat
import hudson.model.Actionable
import hudson.model.Result

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def AGENT = config.AGENT
    def MAVEN_SETTINGS = config.MAVEN_SETTINGS
    def git = new org.mahindra.Git()
    def framework = config.FRAMEWORK
    def pipelineStage = ""

    try {
        podTemplate(
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [containerTemplate(name: 'jnlp', image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:mss-cli', nodeSelector: 'node: "primary"')],
            volumes: [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                      persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
            ]) {
            node(POD_LABEL) {
                currentBuild.result = 'SUCCESS'
                stage('Checkout source code') {
                    container('jnlp') {
                        pipelineStage = "${STAGE_NAME}"
                        git.gitCheckout(5)
                        echo "Status ${currentBuild.result}"
                    }
                }
                
                def settings = ""
                def mvnPackageCommand = "mvn -e -B -DskipTests package"
                if(MAVEN_SETTINGS != null && MAVEN_SETTINGS != "") {
                    settings = "-v ${WORKSPACE}/${MAVEN_SETTINGS}:/root/.m2/settings.xml"
                    mvnPackageCommand = "mvn -e -B -DskipTests package -s settings.xml"
                }
                
                def frameworkSpecificCommand = ""
                if(framework != null) {
                    if(framework == "SPRING_BOOT") {
                        frameworkSpecificCommand = "chmod 777 ./mvnw"
                    }
                }
                
                docker.image("maven:3.5.2-jdk-8-alpine").inside("--net=host --user root --privileged -e JAVA_TOOL_OPTIONS='-Duser.home=m2' ${settings}") {
                    stage('Code Build') {
                        container('jnlp') {
                            pipelineStage = "${STAGE_NAME}"
                            
                            sh "mvn clean"
                            sh "mvn -N io.takari:maven:wrapper"
                            
                            if(frameworkSpecificCommand != "") {
                                sh "${frameworkSpecificCommand}"
                            }
                            
                            sh "${mvnPackageCommand}"
                            
                            echo "Code build completed successfully"
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