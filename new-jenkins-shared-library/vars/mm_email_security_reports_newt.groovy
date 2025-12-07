import java.text.SimpleDateFormat

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def AGENT = config.AGENT ?: ''
    def EMAIL_RECIPIENTS = config.EMAIL_RECIPIENTS ?: 'verma.pranshu@mahindra.com'
    def ATTACHMENTS_PATTERN = config.ATTACHMENTS_PATTERN ?: 'all_security_reports.zip,combined_security_report.txt'
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
                    stage('Email Security Reports') {
                        // Ensure reports are available
                        sh """
                            echo "Available files:"
                            ls -la
                            echo "Security reports directory:"
                            ls -la security_reports/ || echo "No security_reports directory"
                        """

                        // Get combined report content
                        def reportContent = ""
                        if (fileExists('combined_security_report.txt')) {
                            reportContent = readFile('combined_security_report.txt')
                        } else {
                            reportContent = "No combined security report available"
                        }

                        // Prepare email
                        def emailSubject = "Security Scan Report - ${env.JOB_NAME} #${env.BUILD_NUMBER} - ${currentBuild.result ?: 'SUCCESS'}"
                        
                        emailext(
                            subject: emailSubject,
                            body: """
                                <html>
                                <body>
                                    <h2>Security Scan Report</h2>
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

                        echo "Security reports emailed successfully to ${EMAIL_RECIPIENTS}"
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