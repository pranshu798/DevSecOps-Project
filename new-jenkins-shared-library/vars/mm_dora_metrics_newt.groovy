// mm_dora_metrics_newt.groovy
import jenkins.model.Jenkins
import java.text.SimpleDateFormat

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def AGENT = config.AGENT ?: ""
    def TEAMS_WEBHOOK_CREDENTIAL_ID = config.TEAMS_WEBHOOK_CREDENTIAL_ID ?: 'TEAMS_WEBHOOK_URL'
    def JOB_NAME_FILTER = config.JOB_NAME_FILTER
    def DAYS_BACK = config.DAYS_BACK ?: 2
    def ONLY_ON_SUCCESS = config.ONLY_ON_SUCCESS ?: false

    // Skip DORA metrics if build failed and ONLY_ON_SUCCESS is true
    if (ONLY_ON_SUCCESS && (currentBuild?.result?.toString() == 'FAILURE')) {
        echo "Skipping DORA metrics report (build failed and ONLY_ON_SUCCESS=true)"
        return
    }

    try {
        if (AGENT) {
            node(AGENT) {
                generateAndPostMetrics(TEAMS_WEBHOOK_CREDENTIAL_ID, JOB_NAME_FILTER, DAYS_BACK)
            }
        } else {
            // Use the same pod template pattern as your other shared libraries
            podTemplate(
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
                        generateAndPostMetrics(TEAMS_WEBHOOK_CREDENTIAL_ID, JOB_NAME_FILTER, DAYS_BACK)
                    }
                }
            }
        }
    } catch (Exception e) {
        // Log but do not fail the pipeline
        echo "Failed to generate DORA metrics: ${e.getMessage()}"
        e.printStackTrace()
    }
}

def generateAndPostMetrics(String credentialId, String jobNameFilter, int daysBack) {
    stage('Generate DORA Metrics Report') {
        withCredentials([string(credentialsId: credentialId, variable: 'TEAMS_WEBHOOK')]) {
            collectAndPostDORAMetrics(TEAMS_WEBHOOK, jobNameFilter, daysBack)
        }
    }
}

def collectAndPostDORAMetrics(String webhookUrl, String jobNameFilter, int daysBack) {
    echo "📊 Collecting DORA metrics for the last ${daysBack} days..."

    // capture job name safely from environment and pass it down
    def jobName = env.JOB_NAME ?: null

    Map metrics = [:]
    try {
        metrics = calculateDORAMetrics(jobName, jobNameFilter, daysBack)
    } catch (Exception e) {
        echo "❌ Error while calculating DORA metrics: ${e.getMessage()}"
        e.printStackTrace()
        // ensure we have a fallback metrics map so postMetricsToTeams can still run safely
        metrics = [
            totalBuilds: 0,
            successfulBuilds: 0,
            failedBuilds: 0,
            deploymentFrequency: 0.0,
            failureRate: 0.0,
            successRate: 0.0,
            mttr: 0.0,
            mtbf: 0.0,
            leadTime: 0.0,
            buildsByEnvironment: [dev: 0, uat: 0, prod: 0, other: 0],
            buildTrend: "insufficient_data",
            period: "${daysBack} days",
            timestamp: new Date().format("yyyy-MM-dd HH:mm:ss"),
            message: "Error calculating metrics: ${e.getMessage()}"
        ]
    }

    postMetricsToTeams(webhookUrl, metrics, daysBack)
}

@NonCPS
def calculateDORAMetrics(String currentJobName, String jobNameFilter, int daysBack) {
    def now = new Date()
    def cutoffTime = now.time - ((long)daysBack * 24L * 60L * 60L * 1000L)

    def builds = []

    def jenkinsInstance = Jenkins.getInstanceOrNull()
    if (jenkinsInstance == null) {
        return [
            totalBuilds: 0,
            successfulBuilds: 0,
            failedBuilds: 0,
            deploymentFrequency: 0.0,
            failureRate: 0.0,
            successRate: 0.0,
            mttr: 0.0,
            mtbf: 0.0,
            leadTime: 0.0,
            buildsByEnvironment: [dev: 0, uat: 0, prod: 0, other: 0],
            buildTrend: "insufficient_data",
            period: "${daysBack} days",
            timestamp: new Date().format("yyyy-MM-dd HH:mm:ss"),
            message: "Jenkins instance not available"
        ]
    }

    def currentJob = null
    if (currentJobName) {
        try {
            currentJob = jenkinsInstance.getItemByFullName(currentJobName)
        } catch (Exception ignored) {
            currentJob = null
        }
    }

    if (currentJob) {
        def parentFolder = currentJob.getParent()
        if (parentFolder) {
            def allJobs = parentFolder.getAllJobs()
            allJobs.each { job ->
                try {
                    if (job?.builds) {
                        job.builds.each { build ->
                            try {
                                if ((build?.getTimeInMillis() ?: 0L) >= cutoffTime) {
                                    if (build != null) builds.add(build)
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // include builds from the current job as well
        try {
            if (currentJob.builds) {
                currentJob.builds.each { build ->
                    try {
                        if ((build?.getTimeInMillis() ?: 0L) >= cutoffTime) {
                            if (build != null) builds.add(build)
                        }
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
    } else {
        // If currentJob not found, attempt to scan all top-level jobs (best effort)
        try {
            jenkinsInstance.getAllItems().each { job ->
                try {
                    if (job?.builds) {
                        job.builds.each { build ->
                            try {
                                if ((build?.getTimeInMillis() ?: 0L) >= cutoffTime) {
                                    if (build != null) builds.add(build)
                                }
                            } catch (Exception ignored) { }
                        }
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }

    // apply optional job name filter (if provided)
    if (jobNameFilter) {
        builds = builds.findAll { b ->
            try {
                def parentName = b.getParent()?.getFullName()?.toLowerCase()
                return parentName?.contains(jobNameFilter.toLowerCase())
            } catch (Exception ignored) {
                return false
            }
        }
    }

    // deduplicate builds (some builds may have been added twice)
    builds = builds.unique { a, b ->
        try {
            return (a?.getId() ?: "") <=> (b?.getId() ?: "")
        } catch (Exception ignored) {
            return 0
        }
    }

    int totalBuilds = builds.size()

    // Null-safe checks for result comparisons
    def successfulBuilds = builds.findAll { it?.result?.toString() == 'SUCCESS' }
    def failedBuilds = builds.findAll { it?.result?.toString() == 'FAILURE' }

    double deploymentFrequency = totalBuilds > 0 ? (totalBuilds / (double) daysBack) : 0.0
    double failureRate = totalBuilds > 0 ? (failedBuilds.size() / (double) totalBuilds * 100.0) : 0.0
    double successRate = totalBuilds > 0 ? (successfulBuilds.size() / (double) totalBuilds * 100.0) : 0.0
    double mttr = calculateMTTR(failedBuilds)
    double mtbf = calculateMTBF(builds)
    double leadTime = calculateAverageLeadTime(builds)
    def buildsByEnvironment = groupBuildsByEnvironment(builds)
    def buildTrend = calculateBuildTrend(builds, daysBack)

    return [
        totalBuilds: totalBuilds,
        successfulBuilds: successfulBuilds.size(),
        failedBuilds: failedBuilds.size(),
        deploymentFrequency: deploymentFrequency,
        failureRate: failureRate,
        successRate: successRate,
        mttr: mttr,
        mtbf: mtbf,
        leadTime: leadTime,
        buildsByEnvironment: buildsByEnvironment,
        buildTrend: buildTrend,
        period: "${daysBack} days",
        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss")
    ]
}

@NonCPS
def calculateMTTR(List failedBuilds) {
    if (failedBuilds == null || failedBuilds.isEmpty()) return 0.0

    def recoveryTimes = []

    failedBuilds.each { failedBuild ->
        try {
            def nextBuild = failedBuild?.getNextBuild()
            while (nextBuild != null) {
                if (nextBuild?.result?.toString() == 'SUCCESS') {
                    long timeDiff = (nextBuild.getTimeInMillis() - failedBuild.getTimeInMillis())
                    recoveryTimes.add(timeDiff)
                    break
                }
                nextBuild = nextBuild.getNextBuild()
            }
        } catch (Exception ignored) { }
    }

    if (recoveryTimes.isEmpty()) return 0.0

    long sum = 0L
    recoveryTimes.each { sum += (it ?: 0L) }
    double avgRecoveryTime = sum / (double)recoveryTimes.size()
    return avgRecoveryTime / (1000.0 * 60.0) // minutes
}

@NonCPS
def calculateMTBF(List builds) {
    if (builds == null || builds.size() < 2) return 0.0

    def sortedBuilds = builds.sort { it.getTimeInMillis() ?: 0L }
    def failedBuilds = sortedBuilds.findAll { it?.result?.toString() == 'FAILURE' }

    if (failedBuilds.size() < 2) return 0.0

    def timeBetweenFailures = []
    for (int i = 1; i < failedBuilds.size(); i++) {
        try {
            long timeDiff = failedBuilds[i].getTimeInMillis() - failedBuilds[i - 1].getTimeInMillis()
            timeBetweenFailures.add(timeDiff)
        } catch (Exception ignored) { }
    }

    if (timeBetweenFailures.isEmpty()) return 0.0

    long sum = 0L
    timeBetweenFailures.each { sum += (it ?: 0L) }
    double avgMTBF = sum / (double)timeBetweenFailures.size()
    return avgMTBF / (1000.0 * 60.0 * 60.0) // hours
}

@NonCPS
def calculateAverageLeadTime(List builds) {
    if (builds == null || builds.isEmpty()) return 0.0

    long totalDuration = 0L
    builds.each { b ->
        try {
            totalDuration += (b?.getDuration() ?: 0L)
        } catch (Exception ignored) { }
    }
    double avgDuration = totalDuration / (double)builds.size()
    return avgDuration / (1000.0 * 60.0) // minutes
}

@NonCPS
def groupBuildsByEnvironment(List builds) {
    def envCounts = [dev: 0, uat: 0, prod: 0, other: 0]

    builds.each { build ->
        try {
            def jobName = build?.getParent()?.getFullName()?.toLowerCase() ?: ""
            if (jobName.contains('gcp_dev') || jobName.contains('/dev')) {
                envCounts.dev++
            } else if (jobName.contains('gcp_uat') || jobName.contains('/uat')) {
                envCounts.uat++
            } else if (jobName.contains('gcp_prod') || jobName.contains('/prod')) {
                envCounts.prod++
            } else {
                envCounts.other++
            }
        } catch (Exception e) {
            envCounts.other++
        }
    }

    return envCounts
}

@NonCPS
def calculateBuildTrend(List builds, int daysBack) {
    if (builds == null || builds.size() < 4) return "insufficient_data"

    try {
        def sortedBuilds = builds.sort { it.getTimeInMillis() ?: 0L }
        int midPoint = (int) (sortedBuilds.size() / 2)

        def firstHalf = sortedBuilds[0..<midPoint]
        def secondHalf = sortedBuilds[midPoint..-1]

        def firstHalfSuccess = firstHalf.findAll { it?.result?.toString() == 'SUCCESS' }
        def secondHalfSuccess = secondHalf.findAll { it?.result?.toString() == 'SUCCESS' }

        double firstHalfSuccessRate = firstHalf.size() > 0 ? (firstHalfSuccess.size() / (double) firstHalf.size()) * 100.0 : 0.0
        double secondHalfSuccessRate = secondHalf.size() > 0 ? (secondHalfSuccess.size() / (double) secondHalf.size()) * 100.0 : 0.0

        if (secondHalfSuccessRate > firstHalfSuccessRate + 5.0) {
            return "improving"
        } else if (secondHalfSuccessRate < firstHalfSuccessRate - 5.0) {
            return "declining"
        } else {
            return "stable"
        }
    } catch (Exception e) {
        return "insufficient_data"
    }
}

def postMetricsToTeams(String webhookUrl, Map metrics, int daysBack) {
    def trendEmoji = [
        improving: "📈",
        declining: "📉",
        stable: "➡️",
        insufficient_data: "📊"
    ]

    def trendText = [
        improving: "Improving ✨",
        declining: "Needs Attention ⚠️",
        stable: "Stable",
        insufficient_data: "Insufficient Data"
    ]

    def themeColor = "00FF00"
    try {
        double failureRate = (metrics.failureRate ?: 0.0) as double
        if (failureRate > 20.0) {
            themeColor = "FF0000"
        } else if (failureRate > 10.0) {
            themeColor = "FFA500"
        }
    } catch (Exception ignored) { }

    def recommendations = getRecommendations(metrics)

    def totalBuilds = metrics.totalBuilds ?: 0
    def successfulBuilds = metrics.successfulBuilds ?: 0
    def failedBuilds = metrics.failedBuilds ?: 0
    def deploymentFrequency = metrics.deploymentFrequency ?: 0.0
    def successRate = metrics.successRate ?: 0.0
    def failureRate = metrics.failureRate ?: 0.0
    def mttr = metrics.mttr ?: 0.0
    def mtbf = metrics.mtbf ?: 0.0
    def leadTime = metrics.leadTime ?: 0.0

    def payload = [
        "@type": "MessageCard",
        "@context": "http://schema.org/extensions",
        "summary": "DORA Metrics Report - Last ${daysBack} Days",
        "themeColor": themeColor,
        "title": "📊 DORA Metrics Dashboard - ${env.JOB_NAME ?: 'pipeline'}",
        "sections": [
            [
                "activityTitle": "**DevOps Performance Metrics**",
                "activitySubtitle": "Period: Last ${daysBack} days | Generated: ${metrics.timestamp ?: new Date().format('yyyy-MM-dd HH:mm:ss')}",
                "facts": [
                    ["name": "📦 Total Builds", "value": "${totalBuilds} builds"],
                    ["name": "🚀 Deployment Frequency", "value": "${String.format('%.2f', deploymentFrequency)} deployments/day"],
                    ["name": "✅ Success Rate", "value": "${String.format('%.1f', successRate)}% (${successfulBuilds}/${totalBuilds})"],
                    ["name": "❌ Failure Rate", "value": "${String.format('%.1f', failureRate)}% (${failedBuilds}/${totalBuilds})"],
                    ["name": "⏱️ Mean Time To Recovery (MTTR)", "value": "${(mttr > 0.0) ? String.format('%.1f', mttr) + ' minutes' : 'No failures to recover from'}"],
                    ["name": "⏳ Mean Time Between Failures (MTBF)", "value": "${(mtbf > 0.0) ? String.format('%.1f', mtbf) + ' hours' : 'Insufficient failure data'}"],
                    ["name": "📏 Average Lead Time", "value": "${String.format('%.1f', leadTime)} minutes"]
                ]
            ],
            [
                "activityTitle": "**🌍 Builds by Environment**",
                "facts": [
                    ["name": "🟢 Development (gcp_dev)", "value": "${metrics.buildsByEnvironment?.dev ?: 0} builds"],
                    ["name": "🟡 UAT (gcp_uat)", "value": "${metrics.buildsByEnvironment?.uat ?: 0} builds"],
                    ["name": "🔴 Production (gcp_prod)", "value": "${metrics.buildsByEnvironment?.prod ?: 0} builds"],
                    ["name": "⚪ Other", "value": "${metrics.buildsByEnvironment?.other ?: 0} builds"]
                ]
            ],
            [
                "activityTitle": "**📈 Trend Analysis**",
                "text": "${trendEmoji[metrics.buildTrend ?: 'insufficient_data']} **${trendText[metrics.buildTrend ?: 'insufficient_data']}** over the reporting period"
            ],
            [
                "activityTitle": "**💡 Insights & Recommendations**",
                "text": recommendations
            ]
        ]
    ]

    def jsonPayload = groovy.json.JsonOutput.toJson(payload)

    try {
        sh """
            curl -H 'Content-Type: application/json' \
                 -d '${jsonPayload.replace("'", "'\"'\"'")}' \
                 '${webhookUrl}'
        """
        echo "✅ DORA metrics posted to Teams successfully!"
    } catch (Exception e) {
        echo "❌ Failed to post metrics to Teams: ${e.message}"
    }
}

def getRecommendations(Map metrics) {
    def recommendations = []

    double deploymentFrequency = (metrics.deploymentFrequency ?: 0.0) as double
    double failureRate = (metrics.failureRate ?: 0.0) as double
    double mttr = (metrics.mttr ?: 0.0) as double
    double leadTime = (metrics.leadTime ?: 0.0) as double
    int totalBuilds = (metrics.totalBuilds ?: 0) as int

    if (deploymentFrequency < 1.0) {
        recommendations.add("⚠️ **Low deployment frequency** (${String.format('%.2f', deploymentFrequency)}/day). Consider increasing release cadence.")
    } else if (deploymentFrequency > 5.0) {
        recommendations.add("✨ **Excellent deployment frequency!** Achieving continuous deployment.")
    }

    if (failureRate > 20.0) {
        recommendations.add("🚨 **Critical: High failure rate** (${String.format('%.1f', failureRate)}%). Focus on test coverage.")
    } else if (failureRate > 10.0) {
        recommendations.add("⚠️ **Moderate failure rate** (${String.format('%.1f', failureRate)}%). Improve automated testing.")
    } else if (failureRate < 5.0 && totalBuilds > 0) {
        recommendations.add("✅ **Excellent build stability** (${String.format('%.1f', failureRate)}% failure rate)!")
    }

    if (mttr > 60.0 && mttr < 999999.0) {
        recommendations.add("⏱️ **MTTR needs improvement** (${String.format('%.0f', mttr)} min). Focus on faster recovery.")
    } else if (mttr > 0.0 && mttr < 15.0) {
        recommendations.add("⚡ **Outstanding MTTR!** Quick recovery from failures.")
    }

    if (leadTime > 30.0) {
        recommendations.add("🌐 **Long build times** (${String.format('%.1f', leadTime)} min). Consider pipeline optimization.")
    } else if (leadTime < 10.0 && totalBuilds > 0) {
        recommendations.add("⚡ **Fast build times!** Well-optimized pipeline.")
    }

    if (recommendations.isEmpty()) {
        return "📊 All metrics are within acceptable ranges. Continue monitoring for trends."
    }

    return recommendations.join("\n\n")
}