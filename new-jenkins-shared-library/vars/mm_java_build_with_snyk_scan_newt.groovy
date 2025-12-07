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
                def mvnPackageCommend = "mvn -e -B -DskipTests package"
                if(MAVEN_SETTINGS != null && MAVEN_SETTINGS != "") {
                    settings = "-v ${WORKSPACE}/${MAVEN_SETTINGS}:/root/.m2/settings.xml"
                    mvnPackageCommend = "mvn -e -B -DskipTests package -s settings.xml"
                }
                
                def frameworkSpecificCommand = ""
                if(framework != null) {
                    if(framework == "SPRING_BOOT") {
                        frameworkSpecificCommand = "chmod 777 ./mvnw"
                    }
                }
                
                docker.image("maven:3.5.2-jdk-8-alpine").inside("--net=host --user root --privileged -e JAVA_TOOL_OPTIONS='-Duser.home=m2' ${settings}") {
                    stage('Code Build & SCA Scan: Snyk') {
                        container('jnlp') {
                            pipelineStage = "${STAGE_NAME}"
                            withCredentials([
                                string(credentialsId: "snyk-mpaas-api", variable: 'SNYK_TOKEN')]
                            ) {
                                sh "echo \$SNYK_TOKEN" 
                                sh "mvn clean"
                                sh "mvn -N io.takari:maven:wrapper"
                                
                                sh """
                                    ${mvnPackageCommend}
                                    curl https://static.snyk.io/cli/latest/snyk-linux -o snyk
                                    chmod 777 ./snyk
                                """
                                
                                try {
                                    sh """
                                        echo \$SNYK_TOKEN 
                                        ./snyk auth \$SNYK_TOKEN -d
                                        ${frameworkSpecificCommand}
                                        ls
                                        ./snyk test --json-file-output=Snyk_Report.json -d
                                    """
                                } catch(Exception e) {
                                    echo "Error occurred during Snyk test: ${e.message}"
                                }
                                
                                sh """
                                    if [ -f /etc/apt/sources.list.d/kubernetes.list ]; then
                                        sed -i '/kubernetes/d' /etc/apt/sources.list.d/kubernetes.list
                                    fi
                                    apt-get update -y
                                    ls
                                    echo "above is snyk reports"
                                    apt-get remove nodejs npm -y || true
                                    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
                                    apt-get install -y nodejs
                                    npm install snyk-to-html -g
                                    snyk-to-html -i Snyk_Report.json -o Snyk_Report.html
                                """
                                
                                archiveArtifacts 'Snyk_Report.json'
                                archiveArtifacts 'Snyk_Report.html'
                                
                                sh """
                                    apt-get install wkhtmltopdf -y
                                    wkhtmltopdf Snyk_Report.html Snyk_Report.pdf
                                """
                                archiveArtifacts 'Snyk_Report.pdf'
                                
                                // Generate vulnerability count report for Snyk
                                generateSnykVulnerabilityReport()

                                stash includes: 'Snyk_Report.*', name: 'snyk-reports'
                                stash includes: 'vulnerability_count_snyk.txt', name: 'snyk-vulnerability-summary'
                                
                                echo "Find the report in the Artifacts or Snyk Portal"
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

// Function to generate Snyk vulnerability report
def generateSnykVulnerabilityReport() {
    try {
        // First try to parse the JSON file directly
        try {
            def snykResults = readJSON file: "${WORKSPACE}/Snyk_Report.json"
            def vulnerabilities = snykResults.vulnerabilities ?: []
            
            def reportMap = [
                critical: vulnerabilities.count { it.severity?.toLowerCase() == 'critical' },
                high: vulnerabilities.count { it.severity?.toLowerCase() == 'high' },
                medium: vulnerabilities.count { it.severity?.toLowerCase() == 'medium' },
                low: vulnerabilities.count { it.severity?.toLowerCase() == 'low' },
                total: vulnerabilities.size()
            ]
            
            echo "Snyk scan completed. Critical: ${reportMap.critical}, High: ${reportMap.high}, Medium: ${reportMap.medium}, Low: ${reportMap.low}, Total: ${reportMap.total}"
            
            // Create vulnerability_count_snyk.txt
            writeFile file: "${WORKSPACE}/vulnerability_count_snyk.txt", text: """
SNYK VULNERABILITY SCAN SUMMARY
Scan Date: ${new Date().format('yyyy-MM-dd HH:mm:ss')}
Build: ${env.JOB_NAME} #${env.BUILD_NUMBER}

VULNERABILITY COUNTS:
Critical: ${reportMap.critical}
High: ${reportMap.high}
Medium: ${reportMap.medium}
Low: ${reportMap.low}
Total: ${reportMap.total}

STATUS: ${reportMap.critical > 0 || reportMap.high > 10 ? 'ATTENTION REQUIRED' : 'ACCEPTABLE'}
"""
            
        } catch (Exception jsonException) {
            echo "Warning: Could not parse JSON results directly, falling back to console output parsing: ${jsonException.message}"
            
            // Fallback to parsing console output
            def consoleLog = currentBuild.rawBuild.getLog(1000).join('\n')
            
            // Extract counts from console output using regex
            def critical = (consoleLog =~ /Critical: (\d+)/) ? (consoleLog =~ /Critical: (\d+)/)[0][1] : "0"
            def high = (consoleLog =~ /High: (\d+)/) ? (consoleLog =~ /High: (\d+)/)[0][1] : "0"
            def medium = (consoleLog =~ /Medium: (\d+)/) ? (consoleLog =~ /Medium: (\d+)/)[0][1] : "0"
            def low = (consoleLog =~ /Low: (\d+)/) ? (consoleLog =~ /Low: (\d+)/)[0][1] : "0"
            def total = (consoleLog =~ /Total: (\d+)/) ? (consoleLog =~ /Total: (\d+)/)[0][1] : "0"
            
            // Create vulnerability_count_snyk.txt
            writeFile file: "${WORKSPACE}/vulnerability_count_snyk.txt", text: """
SNYK VULNERABILITY SCAN SUMMARY
Scan Date: ${new Date().format('yyyy-MM-dd HH:mm:ss')}
Build: ${env.JOB_NAME} #${env.BUILD_NUMBER}

VULNERABILITY COUNTS:
Critical: ${critical}
High: ${high}
Medium: ${medium}
Low: ${low}
Total: ${total}

STATUS: ${critical.toInteger() > 0 || high.toInteger() > 10 ? 'ATTENTION REQUIRED' : 'ACCEPTABLE'}
"""
            
            echo "Extracted vulnerability counts from console output:"
            echo "Critical: ${critical}, High: ${high}, Medium: ${medium}, Low: ${low}, Total: ${total}"
        }
        
        // Archive the vulnerability report
        archiveArtifacts artifacts: 'vulnerability_count_snyk.txt', allowEmptyArchive: true
        
        // Read the file back to echo it in the logs
        def reportContent = readFile "${WORKSPACE}/vulnerability_count_snyk.txt"
        echo "Snyk Vulnerability Report:\n${reportContent}"
        
        // Set build status based on vulnerability counts
        def reportLines = reportContent.readLines()
        def criticalCount = reportLines.find { it.contains('Critical:') }?.split(':')?.last()?.trim()?.toInteger() ?: 0
        def highCount = reportLines.find { it.contains('High:') }?.split(':')?.last()?.trim()?.toInteger() ?: 0
        
        if (criticalCount > 0) {
            currentBuild.result = 'UNSTABLE'
            echo "❌ Critical vulnerabilities found (${criticalCount}) - marking build as UNSTABLE"
        } else if (highCount > 10) {
            currentBuild.result = 'UNSTABLE'
            echo "⚠️  High number of high-severity vulnerabilities (${highCount}) - marking build as UNSTABLE"
        } else {
            echo "✅ Snyk scan passed - no critical vulnerabilities found"
        }
        
    } catch (Exception e) {
        echo "Error generating Snyk vulnerability report: ${e.message}"
        currentBuild.result = 'UNSTABLE'
    }
}