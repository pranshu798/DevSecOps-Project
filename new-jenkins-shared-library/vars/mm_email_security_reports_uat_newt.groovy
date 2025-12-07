import java.text.SimpleDateFormat

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def AGENT = config.AGENT ?: ''
    def EMAIL_RECIPIENTS = config.EMAIL_RECIPIENTS ?: 'verma.pranshu@mahindra.com'
    def ATTACHMENTS_PATTERN = config.ATTACHMENTS_PATTERN ?: 'dast_security_reports.zip,dast_summary_report.txt,vulnerability_count_acunetix.txt,tollgate_report.txt'
    def FAIL_ON_EMAIL_ERROR = config.FAIL_ON_EMAIL_ERROR ?: false

    try {
        podTemplate(
            nodeSelector: 'cloud.google.com/gke-nodepool=spot-node-pool-1',
            containers: [containerTemplate(name: 'jnlp', image: 'asia.gcr.io/common-infra-services/jenkins-agent-updated:mss-cli')],
            volumes: [hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock'),
                      persistentVolumeClaim(claimName: 'jenkinsagentpvc', mountPath: '/home/jenkins/agent', readOnly: false)
            ]) {
            node(POD_LABEL) {
                container('jnlp') {
                    stage('Email DAST & Tollgate Reports') {
                        // Ensure reports are available
                        sh """
                            echo "Available files:"
                            ls -la
                            echo "DAST reports directory:"
                            ls -la dast_reports/ || echo "No dast_reports directory"
                        """

                        // Get DAST summary report content
                        def dastReportContent = ""
                        if (fileExists('dast_summary_report.txt')) {
                            dastReportContent = readFile('dast_summary_report.txt')
                        } else {
                            dastReportContent = "No DAST summary report available"
                        }

                        // Get Acunetix vulnerability count
                        def acunetixVulnCount = ""
                        if (fileExists('vulnerability_count_acunetix.txt')) {
                            acunetixVulnCount = readFile('vulnerability_count_acunetix.txt')
                        } else {
                            acunetixVulnCount = "No Acunetix vulnerability count available"
                        }

                        // Get Tollgate report content
                        def tollgateReportContent = ""
                        if (fileExists('tollgate_report.txt')) {
                            tollgateReportContent = readFile('tollgate_report.txt')
                        } else {
                            tollgateReportContent = "No tollgate report available"
                        }

                        // Combine all reports
                        def reportContent = """
DAST Summary Report:
${dastReportContent}

Acunetix Vulnerability Count:
${acunetixVulnCount}

Tollgate Report:
${tollgateReportContent}
"""

                        // Prepare email subject
                        def emailSubject = "UAT Security Report - DAST & Tollgate - ${env.JOB_NAME} #${env.BUILD_NUMBER} - ${currentBuild.result ?: 'SUCCESS'}"
                        
                        emailext(
                            subject: emailSubject,
                            body: """
                                <html>
                                <body>
                                    <h2>UAT Security Scan Report - DAST & Tollgate</h2>
                                    <p>Build: <a href="${env.BUILD_URL}">${env.JOB_NAME} #${env.BUILD_NUMBER}</a></p>
                                    <p>Status: <strong>${currentBuild.result ?: 'SUCCESS'}</strong></p>
                                    <p>Generated at: ${new Date().format('yyyy-MM-dd HH:mm:ss')}</p>
                                    <hr>
                                    <h3>Summary</h3>
                                    <pre>${reportContent}</pre>
                                    <hr>
                                    <p>Detailed reports are attached to this email.</p>
                                    <p><em>This is an automated message from Jenkins CI/CD pipeline.</em></p>
                                </body>
                                </html>
                            """,
                            to: EMAIL_RECIPIENTS,
                            attachmentsPattern: ATTACHMENTS_PATTERN,
                            mimeType: 'text/html'
                        )

                        echo "UAT security reports (DAST & Tollgate) emailed successfully to ${EMAIL_RECIPIENTS}"
                    }
                }
            }
        }
    } catch (Exception e) {
        echo "Failed to send email: ${e.message}"
        if (FAIL_ON_EMAIL_ERROR) {
            error("Email sending failed and FAIL_ON_EMAIL_ERROR is true")
        } else {
            currentBuild.result = 'UNSTABLE'
            echo "Continuing pipeline despite email failure"
        }
    }
}