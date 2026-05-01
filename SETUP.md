# Jenkins Pipeline Setup Guide
## EKS Trunk-Based Deployment – Dev / UAT / Prod

---

## Repository Layout (DevSecOps repo)

```
DevSecOps-Project/
├── Jenkinsfile                         ← Pipeline definition
├── Dockerfile                          ← Multi-stage Spring Boot image
└── helm/
    └── springboot-app/
        ├── Chart.yaml
        ├── templates/
        │   ├── deployment.yaml
        │   ├── service.yaml
        │   ├── ingress.yaml
        │   ├── hpa.yaml
        │   ├── pdb.yaml
        │   └── _helpers.tpl
        ├── values.yaml                 ← Base defaults
        ├── values-dev.yaml             ← DEV overrides
        ├── values-uat.yaml             ← UAT overrides
        └── values-prod.yaml            ← PROD overrides
```

---

## 1 – Jenkins Credentials to Create

Go to **Manage Jenkins → Credentials → (global)**

| ID                  | Kind                  | Value                                    |
|---------------------|-----------------------|------------------------------------------|
| `aws-credentials`   | AWS Credentials       | IAM Access Key + Secret for ECR/EKS     |
| `aws-account-id`    | Secret Text           | Your 12-digit AWS account number         |
| `sonar-token`       | Secret Text           | SonarQube user token                     |
| `github-token`      | Username + Password   | GitHub PAT with repo read access         |

---

## 2 – Jenkins Global Tools

**Manage Jenkins → Tools**

| Tool            | Name        | Version  |
|-----------------|-------------|----------|
| Maven           | `maven3`    | 3.9.x    |
| SonarQube Scanner | `sonar-scanner` | latest |
| OWASP DC        | `OWASP-DC`  | latest   |

---

## 3 – SonarQube Server Config

**Manage Jenkins → System → SonarQube servers**
- Name: `SonarQube-Server`
- URL: `http://<your-sonar-host>:9000`
- Token: (select `sonar-token` credential)

---

## 4 – Jenkins Node Requirements

Label your Jenkins build agent(s) with `eks-agent`.

Tools that must be installed on the agent:
```bash
# Docker
docker --version

# AWS CLI v2
aws --version

# kubectl
kubectl version --client

# Helm 3
helm version

# Trivy
trivy --version
```

---

## 5 – AWS IAM Permissions (minimum)

The `aws-credentials` IAM user/role needs:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:PutImage",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "eks:DescribeCluster",
        "eks:ListClusters"
      ],
      "Resource": "*"
    }
  ]
}
```

Plus `kubectl` access via EKS RBAC (add IAM user to `aws-auth` ConfigMap).

---

## 6 – ECR Repository Setup

```bash
# Create ECR repo (one-time)
aws ecr create-repository \
    --repository-name pranshu-springboot-app \
    --region ap-south-1 \
    --image-scanning-configuration scanOnPush=true
```

---

## 7 – EKS Clusters (one-time)

```bash
# Create 3 clusters (uses eksctl)
for ENV in dev uat prod; do
  eksctl create cluster \
    --name pranshu-eks-$ENV \
    --region ap-south-1 \
    --nodegroup-name ng-$ENV \
    --node-type t3.medium \
    --nodes-min 2 \
    --nodes-max 4 \
    --managed
done
```

---

## 8 – Trunk-Based Deployment Flow

```
git push → main branch
         │
         ▼
   [Build + Test]
   [OWASP Scan]
   [SonarQube + Quality Gate]
   [Docker Build]
   [Trivy Scan]
   [Push → ECR]
         │
         ▼
   AUTO: Deploy → DEV
   AUTO: Smoke Test DEV
         │
         ▼
   MANUAL GATE: QA/DevLead approves (24h window)
         │
         ▼
   Deploy → UAT
   Smoke Test UAT
         │
         ▼
   MANUAL GATE: Release Manager approves (72h window)
         │
         ▼
   Deploy → PROD
   Smoke Test PROD
```

**Key rule**: Only the `main` branch deploys to UAT and PROD.
Feature branches only run build + test + scan stages.

---

## 9 – Helm Secrets (Kubernetes)

Create DB secrets in each namespace before first deploy:

```bash
# DEV
kubectl create secret generic db-secret-dev \
  --from-literal=url='jdbc:postgresql://dev-db:5432/appdb' \
  -n dev

# UAT
kubectl create secret generic db-secret-uat \
  --from-literal=url='jdbc:postgresql://uat-db:5432/appdb' \
  -n uat

# PROD
kubectl create secret generic db-secret-prod \
  --from-literal=url='jdbc:postgresql://prod-db:5432/appdb' \
  -n prod
```

---

## 10 – Rollback

```bash
# Helm rollback (one command)
helm rollback springboot-app 0 -n <namespace>
# 0 = previous revision, or use: helm history springboot-app -n <ns>
```
