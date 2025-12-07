import java.text.SimpleDateFormat
import hudson.model.Actionable
import hudson.model.Result
def call(body)
{
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def AGENT = config.AGENT
    def MAVEN_SETTINGS = config.MAVEN_SETTINGS
    def testingType = config.testingType
    def git = new org.mahindra.Git()
    def framework = config.FRAMEWORK

    try {
        podTemplate(
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [containerTemplate(name: 'jnlp', image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:mss-cli', nodeSelector: 'node: "primary"')],
            volumes: [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                      persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
            ]) {
            node(POD_LABEL) {
                currentBuild.result = 'SUCCESS'
                stage('Checkout source code')
                    {
                        container('jnlp') {
                            pipelineStage = "${STAGE_NAME}"
                            git.gitCheckout(5)
                            echo "Status ${currentBuild.result}"
                        }
                    }
                settings = ""
                mvnPackageCommend = "mvn -e -B -DskipTests package"
                if(MAVEN_SETTINGS != null && MAVEN_SETTINGS != "") {
                    settings = "-v ${WORKSPACE}/${MAVEN_SETTINGS}:/root/.m2/settings.xml"
                    mvnPackageCommend = "mvn -e -B -DskipTests package -s settings.xml"
                }
                frameworkSpecificCommand = ""
                if(framework!=null){
                    if(framework=="SPRING_BOOT"){
                        frameworkSpecificCommand = "chmod 777 ./mvnw"
                    }
                }
                docker.image("maven:3.5.2-jdk-8-alpine").inside("--net=host --user root --privileged -e JAVA_TOOL_OPTIONS='-Duser.home=m2' ${settings}") {
                    stage('Java Build') {
                        container('jnlp') {
                            sh "mvn clean"
                            sh "mvn -N io.takari:maven:wrapper"
                            sh """
                                ${mvnPackageCommend}
                            """
                            if(framework!=null && framework=="SPRING_BOOT"){
                                sh "${frameworkSpecificCommand}"
                            }
                            echo "Build completed successfully"
                        }
                    }
                }
                if(testingType!=null&&testingType.equalsIgnoreCase("unit_test")){
                  stage('unit tests') {
                    container('jnlp') {
                        catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS') {
                            sh "mvn test"
                        }
                    sh "pwd"
                    sh "ls -la"
                    def testReportFiles = sh(
                            script: "find target/surefire-reports -name '*.xml'",
                            returnStdout: true
                    ).trim()

                    def tempDir = sh(
                            script: 'mktemp -d',
                            returnStdout: true
                    ).trim()

                    sh "mkdir -p ${tempDir}"

                    testReportFiles.split('\n').each { file ->
                        def fileName = file.tokenize('/').last()
                        sh "cp ${file} ${tempDir}/${fileName}"
                    }

                    sh "sudo apt-get update"
                    sh "rm -rf ${tempDir}"
                    }
                 }
                }
            }
        }
    }
    catch (Exception err) {
        println err.getMessage()
        failedStage = "${pipelineStage}"
        echo "Build Failed at ${pipelineStage}"
        currentBuild.result = 'FAILURE'
        echo "Error caught"
    }
}