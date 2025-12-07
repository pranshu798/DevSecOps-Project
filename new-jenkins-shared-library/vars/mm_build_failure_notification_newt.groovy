def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def AGENT = config.AGENT ?: ""
    def TEAMS_WEBHOOK_CREDENTIAL_ID = config.TEAMS_WEBHOOK_CREDENTIAL_ID ?: 'TEAMS_WEBHOOK_URL'
    def JOB_NAME = env.JOB_NAME
    def BUILD_NUMBER = env.BUILD_NUMBER
    def BUILD_URL = env.BUILD_URL
    def BUILD_STATUS = currentBuild.result ?: 'SUCCESS'
    def ERROR_MESSAGE = config.ERROR_MESSAGE ?: currentBuild.description ?: "Build failed - check logs for details"

    // Only proceed if build failed
    if (BUILD_STATUS != 'FAILURE') {
        echo "Build successful - skipping Teams notification"
        return
    }

    try {
        if (AGENT) {
            node(AGENT) {
                sendFailureNotification(TEAMS_WEBHOOK_CREDENTIAL_ID, JOB_NAME, BUILD_URL, BUILD_NUMBER, ERROR_MESSAGE)
            }
        } else {
            podTemplate(
                nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
                containers: [
                    containerTemplate(
                        name: 'jnlp',
                        image: 'asia-south1-docker.pkg.dev/common-infra-services/jenkins-agent-updated/agent:latest'
                    )
                ],
                volumes: [
                    hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                    persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
                ]
            ) {
                node(POD_LABEL) {
                    container('jnlp') {
                        sendFailureNotification(TEAMS_WEBHOOK_CREDENTIAL_ID, JOB_NAME, BUILD_URL, BUILD_NUMBER, ERROR_MESSAGE)
                    }
                }
            }
        }
    } catch (Exception e) {
        echo "Failed to send notification: ${e.getMessage()}"
        // Don't fail the pipeline if notification fails
    }
}

def sendFailureNotification(String credentialId, String jobName, String buildUrl, String buildNumber, String errorMessage) {
    stage('Send Build Failure Notification') {
        withCredentials([string(credentialsId: credentialId, variable: 'TEAMS_WEBHOOK')]) {
            notifyFailureToTeams(TEAMS_WEBHOOK, jobName, buildUrl, buildNumber, errorMessage)
        }
    }
}

def notifyFailureToTeams(String webhookUrl, String jobName, String buildUrl, String buildNumber, String errorMessage) {
    def suggestion = getIntelligentSuggestion(errorMessage, jobName, buildNumber)
    def payload = [
        "@type": "MessageCard",
        "@context": "http://schema.org/extensions",
        "summary": "Jenkins Build Failure",
        "themeColor": "FF0000",
        "title": "🚨 Jenkins Build Failed",
        "sections": [[
            "activityTitle": "**Job:** ${jobName}",
            "activitySubtitle": "**Build #${buildNumber}**",
            "text": "**Build URL:** [Click here](${buildUrl})\n\n**Error Message:** ${errorMessage}\n\n**Suggested Fixes:** ${suggestion}"
        ]]
    ]
    def jsonPayload = groovy.json.JsonOutput.toJson(payload)
    sh """
        curl -H 'Content-Type: application/json' \
             -d '${jsonPayload}' \
             '${webhookUrl}'
    """
}

def getIntelligentSuggestion(String errorMessage, String jobName, String buildNumber) {
    try {
        def logs = getRecentConsoleLog(100)
        def context = analyzeLogContext(logs, errorMessage, jobName)
        return generateContextualSuggestion(errorMessage, context, jobName)
    } catch (Exception e) {
        echo "Error generating intelligent suggestion: ${e.message}"
        return getFallbackSuggestion(errorMessage)
    }
}

def getRecentConsoleLog(int lines) {
    try {
        def logText = ""
        if (currentBuild.rawBuild != null) {
            logText = currentBuild.rawBuild.getLog(Math.min(lines, 1000)).join('\n')
        } else {
            logText = "Console logs not available in current context. Check Jenkins build logs directly."
        }
        return logText ?: "No logs available"
    } catch (Exception e) {
        return "Unable to retrieve console logs: ${e.message}"
    }
}

def analyzeLogContext(String logs, String errorMessage, String jobName) {
    def context = [:]
    def lowerLogs = logs.toLowerCase()
    def lowerError = errorMessage.toLowerCase()
    
    // Technology detection
    context.hasDocker = lowerLogs.contains("docker") || lowerError.contains("docker")
    context.hasMaven = lowerLogs.contains("mvn") || lowerLogs.contains("maven") || jobName.toLowerCase().contains("java")
    context.hasK8s = lowerLogs.contains("kubectl") || lowerLogs.contains("kubernetes") || lowerLogs.contains("k8s")
    context.hasHelm = lowerLogs.contains("helm") || jobName.toLowerCase().contains("helm")
    context.hasGcloud = lowerLogs.contains("gcloud") || lowerLogs.contains("gcp")
    context.hasDotnet = lowerLogs.contains("dotnet") || lowerLogs.contains(".net") || jobName.toLowerCase().contains("dotnet")
    context.hasPython = lowerLogs.contains("python") || lowerLogs.contains("pip") || jobName.toLowerCase().contains("python")
    context.hasNode = lowerLogs.contains("node") || lowerLogs.contains("npm") || jobName.toLowerCase().contains("node")
    
    // Error type detection
    context.hasNetwork = lowerLogs.contains("timeout") || lowerLogs.contains("connection") || lowerLogs.contains("refused")
    context.hasMemory = lowerLogs.contains("memory") || lowerLogs.contains("oom")
    context.hasPermission = lowerLogs.contains("permission") || lowerLogs.contains("forbidden") || lowerLogs.contains("403")
    context.hasDiskSpace = lowerLogs.contains("no space") || lowerLogs.contains("disk full")
    context.hasCompilation = lowerLogs.contains("compilation") || lowerLogs.contains("compile failed")
    
    return context
}

def generateContextualSuggestion(String errorMessage, Map context, String jobName) {
    def suggestions = []
    
    if (context.hasDocker) {
        suggestions.add("Check Docker daemon status and permissions")
        suggestions.add("Verify Docker image names and tags")
    }
    
    if (context.hasMaven) {
        suggestions.add("Check Maven dependencies and repository connectivity")
        suggestions.add("Try running: mvn clean install")
    }
    
    if (context.hasK8s || context.hasHelm) {
        suggestions.add("Verify cluster connectivity and credentials")
        suggestions.add("Check namespace and resource configurations")
    }
    
    if (context.hasNetwork) {
        suggestions.add("Check network connectivity to target services")
        suggestions.add("Verify firewall rules and DNS resolution")
    }
    
    if (context.hasMemory) {
        suggestions.add("Increase JVM heap size or container memory limits")
        suggestions.add("Check for memory leaks in application")
    }
    
    if (context.hasPermission) {
        suggestions.add("Verify file/directory permissions")
        suggestions.add("Check service account IAM roles")
    }
    
    if (suggestions.isEmpty()) {
        suggestions.add("Review the complete build logs for detailed error information")
        suggestions.add("Check recent code changes and configuration updates")
        suggestions.add("Verify all dependencies and services are available")
    }
    
    return "🔧 **Troubleshooting Steps:**\n\n• " + suggestions.take(5).join("\n• ")
}

def getFallbackSuggestion(String errorMessage) {
    return """🔧 **General Troubleshooting Steps:**
    
• Review the complete build logs for detailed error information
• Check recent code changes and configuration updates
• Verify all dependencies and services are available
• Ensure sufficient resources (memory, disk space)
• Validate credentials and access permissions
• Try re-running the build to check for intermittent issues
• Contact DevOps team if the issue persists"""
}