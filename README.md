# Architecture Diagram

![Cloud Architecture Diagram.png](Cloud%20Architecture%20Diagram.png)

# Insurance self-service portal - source code

Two components:

- **upload-app/** - Spring Boot app hosted on EC2 (behind the ALB). Serves a
  one-page upload form and stores files in S3.
- **lambda-processor/** - Java Lambda triggered by S3 `ObjectCreated` events.
  Reads the object's Content-Type, writes a row to RDS, logs to CloudWatch.

## 0. AWS infrastructure setup (do this first)

This code assumes the following AWS resources already exist. Full click-by-click
setup instructions for all of these are in
[`docs/aws-console-walkthrough.md`](docs/aws-console-walkthrough.md), bundled in
this repo so the setup steps travel with the code rather than living only in an
external document.

Summary of what needs to exist before deploying this code:

- **VPC** with 6 subnets across 2 AZs: 2 public, 2 private-application,
  2 private-database
- **Internet Gateway** + route tables (public subnets route to it; private
  subnets don't)
- **S3 Gateway VPC Endpoint** (free) on the private-app route table, and a
  **Secrets Manager Interface VPC Endpoint** (small cost) in the private-app
  subnets - both required since there's no NAT Gateway
- **Security groups**: `alb-sg` (80 from internet), `ec2-sg` (8080 from
  `alb-sg`), `lambda-sg` (no inbound needed), `rds-sg` (3306 from `lambda-sg`
  **only** - not EC2)
- **Network ACLs**: one per subnet tier (public/app/db), each with explicit
  inbound and outbound rules including the ephemeral port range, as a second
  independent layer alongside the security groups above
- **IAM**: an `insurance-portal-devs` group (read-only, 5 services) for human
  team access, plus the two service roles described in sections 3 and 4 below
- **RDS** MySQL instance, private, no public access, with `db/schema.sql`
  applied
- Two **S3 buckets**: one for customer uploads, one for deployment artifacts
  (kept separate on purpose - see section 1 of the walkthrough doc)

Full detail, including exact console navigation, security group rules, and
a troubleshooting appendix for common errors, is in
[`docs/aws-console-walkthrough.md`](docs/aws-console-walkthrough.md).

## 1. Prerequisites

- Java 17, Maven 3.9+
- Every AWS resource listed in section 0 above, already provisioned
- `db/schema.sql` applied to the RDS instance (see section 5 below)

## 2. Build

```bash
# Spring Boot app
cd upload-app
mvn clean package
# -> target/upload-app.jar

# Lambda function (produces a shaded/fat jar for deployment)
cd ../lambda-processor
mvn clean package
# -> target/lambda-processor.jar
```

## 3. Deploy the Spring Boot app to EC2 (behind an ALB + Auto Scaling Group)

The app doesn't run on a single fixed instance - it's deployed via a launch
template used by an Auto Scaling Group, sitting behind an Application Load
Balancer. The ALB is what customers actually reach; instances scale in/out
based on load and are never reached directly.

1. Upload `upload-app.jar` to the artifacts bucket (not the uploads bucket):
   `s3://<artifacts-bucket>/upload-app.jar`.
2. In the launch template's **user data**, install Java 17, pull the jar from
   the artifacts bucket, and run it as a systemd service:

   ```bash
   #!/bin/bash
   set -e
   dnf install -y java-17-amazon-corretto
   mkdir -p /opt/app
   aws s3 cp s3://<artifacts-bucket>/upload-app.jar /opt/app/upload-app.jar

   cat > /etc/systemd/system/upload-app.service << 'EOF'
   [Unit]
   Description=Insurance Portal Upload App
   After=network.target

   [Service]
   User=ec2-user
   Environment=S3_BUCKET_NAME=<uploads-bucket>
   Environment=AWS_REGION=<your-region>
   ExecStart=/usr/bin/java -jar /opt/app/upload-app.jar
   Restart=always

   [Install]
   WantedBy=multi-user.target
   EOF

   systemctl daemon-reload
   systemctl enable upload-app
   systemctl start upload-app
   ```

3. Target group: protocol HTTP, port `8080`, health check path
   `/actuator/health` (exposed by `spring-boot-starter-actuator`, already in
   `pom.xml`).
4. ALB: internet-facing, public subnets, forwards to the target group above.
5. Auto Scaling Group: uses the launch template, deployed in the **private
   application subnets**, attached to the target group.
6. IAM instance role needs **two** separate permissions, not one:
    - `s3:PutObject` on the uploads bucket ARN - what the app itself uses
    - `s3:GetObject` on the *exact* artifacts bucket/key for `upload-app.jar` -
      what the user-data script uses to pull the jar at boot

   Scoping the second one to the exact object key (not a bucket-wide
   wildcard) means this role still can't read anything else in that bucket,
   let alone the uploads bucket. No access keys are stored on the instance -
   the AWS SDK/CLI pick up the role automatically.

## 4. Deploy the Lambda function

1. Upload `lambda-processor.jar` as the Lambda's deployment package.
2. Handler: `com.insurance.lambda.S3UploadProcessor::handleRequest`
3. Runtime: Java 17
4. Attach the Lambda to the VPC's **private application subnets** (both AZs)
   so it can reach RDS privately, and assign it a security group that RDS's
   security group allows on the DB port.
5. Add an S3 trigger: `PUT` events on the uploads bucket.
6. Environment variables - **`DB_HOST`, `DB_PORT`, `DB_NAME` are always
   required as plain environment variables**, regardless of which credential
   path below you use. RDS's auto-generated secret (if you check "Manage
   master credentials in Secrets Manager" when creating the DB) only
   contains `username`/`password` - it does NOT contain host/port/dbname,
   since RDS treats those as connection details rather than credentials.
    - **Recommended (bonus): Secrets Manager** - set `DB_SECRET_ARN` to the
      secret's ARN. The code reads only `username` and `password` from it.
      Grant the Lambda execution role `secretsmanager:GetSecretValue` on that
      one ARN only, and make sure the Secrets Manager Interface VPC Endpoint
      from the prerequisites is in place - without it, this call hangs until
      the Lambda's timeout instead of failing fast.
    - **Simple path:** skip `DB_SECRET_ARN` entirely and set `DB_USER`/
      `DB_PASSWORD` as plain environment variables instead (encrypt with a
      KMS key for anything beyond a demo).
7. IAM role also needs: `s3:GetObject` on the uploads bucket, and CloudWatch
   Logs write permissions (`AWSLambdaBasicExecutionRole` covers this) plus
   `AWSLambdaVPCAccessExecutionRole` for running inside the VPC.

## 5. Database

Apply `db/schema.sql` once, from an instance inside the VPC (RDS has no
public endpoint). The Lambda inserts one row per successful upload.

## 6. Security notes

- EC2 role: `s3:PutObject` on the uploads bucket, plus `s3:GetObject` scoped
  to the one artifacts jar it pulls at boot - nothing else.
- Lambda role: `s3:GetObject` on the uploads bucket, RDS network access via
  SG, optionally `secretsmanager:GetSecretValue` on one secret, plus basic +
  VPC CloudWatch/ENI execution policies.
- RDS security group only allows inbound on the DB port from the Lambda
  security group - not EC2, since the application never queries the
  database directly and Part 5 requires that only Lambda communicate
  with RDS.
- Neither VPC endpoint (S3 Gateway, Secrets Manager Interface) opens any
  inbound path from outside the VPC - they only let resources already
  inside private subnets reach those AWS services without a NAT Gateway.
- All of this maps to the security groups and IAM policies referenced in
  the architecture diagram.

## 7. Verify it works (end-to-end test)

1. Open the ALB's DNS name in a browser - the upload page should load.
2. Upload a test file.
3. S3 console - confirm the file landed in the uploads bucket.
4. Lambda console - Monitor tab - CloudWatch logs - confirm a successful
   run with no errors.
5. Query the database (from an instance inside the VPC, e.g. a temporary
   bastion) - `SELECT * FROM uploads ORDER BY id DESC LIMIT 1;` - confirm
   the new row.

Full troubleshooting for common failures at each of these steps is in
[`docs/aws-console-walkthrough.md`](docs/aws-console-walkthrough.md)'s
appendix.

## 8. Dependencies

**upload-app** (Spring Boot 3.3, Java 17):
- `spring-boot-starter-web` - HTTP server and MVC
- `spring-boot-starter-actuator` - exposes `/actuator/health` for the ALB
  target group health check
- `software.amazon.awssdk:s3` (AWS SDK v2) - S3 client

**lambda-processor** (Java 17, AWS Lambda runtime):
- `com.amazonaws:aws-lambda-java-core` / `aws-lambda-java-events` - Lambda
  handler interface and S3 event types
- `software.amazon.awssdk:s3` - reads uploaded object metadata
- `software.amazon.awssdk:secretsmanager` - fetches DB credentials (bonus
  activity)
- `com.mysql:mysql-connector-j` - JDBC driver for RDS MySQL
- `org.json:json` - parses the Secrets Manager JSON payload
- `maven-shade-plugin` - bundles all of the above into one deployable fat
  jar, since Lambda needs a single self-contained package

Exact versions are pinned in each module's `pom.xml`.
